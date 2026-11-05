package message

import channel.ChannelJoined
import channel.DirectChannelRepository
import channel.Reply
import contact.ContactSyncError
import database.data.local.dao.GroupDao
import database.data.local.dao.BlockedUserDao
import database.data.local.dao.IdentityConflictDao
import database.data.remote.AcknowledgeDirectMessagesBody
import database.data.remote.DirectMessageApi
import database.data.remote.DirectMessageDto
import database.data.remote.ListDirectMessagesResponse
import database.data.remote.MAX_ACKNOWLEDGEMENT_BATCH
import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import group.withGroupJoinLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mls.ApplicationMessageReading
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.isRetryable
import mls.joinGroupFrom
import mls.readApplicationMessage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Takes in the messages waiting for the user and hands each to whichever handler it
 * belongs to.
 *
 * One place decrypts, because decrypting is the only way to find out what a message is:
 * the server stores an opaque string, and what is inside it, a contact card, a notice that
 * somebody removed the user, a contact's new profile key or a request for the user's, the key
 * to an expense or a request for one, is only knowable afterwards. Reading also *consumes*
 * the message, MLS deletes the key that opened it, so a message may be read exactly once and
 * whatever it said has to be acted on there and then.
 *
 * Every message that was read, and every message that never will be, is acknowledged,
 * which is the only thing that deletes it server-side. The exception is one that could not
 * be read yet, the device's own storage failing or the server not saying whether it may be,
 * which leaves it unconsumed: it is left for the server to hand back, and so is everything
 * after it from the same sender, which comes back behind it. Once read, a message is acted
 * on whole, cancelled or not, but a handler that fails after the read loses it: it can
 * never be read again, and the next pass acknowledges it as unreadable.
 *
 * Nothing is read before the user's contacts are back on the device after a login, see
 * [ContactRepository.restore]: a Welcome is only acted on from somebody this device knows,
 * and one from a contact it has not restored yet would be dropped for good.
 *
 * Nor in one of the user's groups, those they are no longer in included, removed or left, see
 * [GroupDao.hasMlsGroup], whichever the sender names: a direct message is between two users,
 * and what one reads in a group of several members its sender is one of, see
 * [DirectChannelRepository.noteReadIn], would have that group become the channel to them.
 * Checked and read under [withGroupJoinLock], which the group sync holds from joining a group
 * to saving it: the library holds the group from the join, and the database names it only once
 * it is saved.
 *
 * [restoreContacts] is [ContactRepository.restore], [listMessages] and [acknowledge] are
 * [DirectMessageApi.getDirectMessages] and [DirectMessageApi.acknowledgeDirectMessages],
 * [client] is [MlsIdentityRepository.client], [isBlocked] is [IdentityConflictDao.isBlocked] or
 * [BlockedUserDao.isBlocked],
 * [isGroup] is [GroupDao.hasMlsGroup], [completeAcceptanceIn] is
 * [ContactRequestRepository.completeAcceptanceIn], [isKnownPeer], [adopt], [noteReadIn] and
 * [claimReply] are those of [DirectChannelRepository], and [replyIn] is
 * [DirectMessageSender.replyIn], or what a test stands in for any of them.
 */
class DirectMessageSync internal constructor(
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val listMessages: suspend () -> Result<ListDirectMessagesResponse, NetworkError>,
    private val acknowledge: suspend (body: AcknowledgeDirectMessagesBody) -> EmptyResult<NetworkError>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val isBlocked: suspend (peerUuid: String) -> Boolean,
    private val isGroup: suspend (mlsGroupId: ByteArray) -> Boolean,
    private val completeAcceptanceIn: suspend (
        client: MlsClient,
        senderUserUuid: String,
        mlsGroupId: ByteArray,
    ) -> EmptyResult<ContactSyncError>,
    private val isKnownPeer: suspend (peerUuid: String) -> Boolean,
    private val adopt: suspend (client: MlsClient, peerUuid: String, mlsGroupId: ByteArray) -> Unit,
    private val noteReadIn: suspend (client: MlsClient, peerUuid: String, mlsGroupId: ByteArray) -> Unit,
    private val claimReply: suspend (peerUuid: String, reply: Reply) -> Boolean,
    private val replyIn: suspend (recipientUserUuid: String, mlsGroupId: ByteArray, payload: ByteArray) -> Unit,
    private val handlers: List<DirectMessageHandler>,
) {
    internal constructor(
        directMessageApi: DirectMessageApi,
        directChannelRepository: DirectChannelRepository,
        contactRepository: ContactRepository,
        contactRequestRepository: ContactRequestRepository,
        directMessageSender: DirectMessageSender,
        mlsIdentityRepository: MlsIdentityRepository,
        identityConflictDao: IdentityConflictDao,
        blockedUserDao: BlockedUserDao,
        groupDao: GroupDao,
        handlers: List<DirectMessageHandler>,
    ) : this(
        restoreContacts = contactRepository::restore,
        listMessages = directMessageApi::getDirectMessages,
        acknowledge = directMessageApi::acknowledgeDirectMessages,
        client = mlsIdentityRepository::client,
        isBlocked = { peerUuid -> identityConflictDao.isBlocked(peerUuid) || blockedUserDao.isBlocked(peerUuid) },
        isGroup = groupDao::hasMlsGroup,
        completeAcceptanceIn = contactRequestRepository::completeAcceptanceIn,
        isKnownPeer = directChannelRepository::isKnownPeer,
        adopt = directChannelRepository::adopt,
        noteReadIn = directChannelRepository::noteReadIn,
        claimReply = directChannelRepository::claimReply,
        replyIn = { recipientUserUuid, mlsGroupId, payload ->
            directMessageSender.replyIn(
                recipientUserUuid = recipientUserUuid,
                mlsGroupId = mlsGroupId,
                payload = payload,
            )
        },
        handlers = handlers,
    )

    /**
     * Serialises [sync]: two passes interleaved could act on one sender's messages out of
     * order, noting a read in an old group after the other adopted a newer Welcome.
     */
    private val mutex = Mutex()

    suspend fun sync(): EmptyResult<DirectMessageSyncError> = mutex.withLock { syncLocked() }

    /**
     * Whether the server holds no message for the user that this device has yet to read.
     * One could carry a Welcome needing the secret of one of the user's key packages, see
     * [mls.KeyPackageRepository]. False when the server could not be asked.
     *
     * Waits for a [sync] already running, whose messages would otherwise still be listed.
     * Only looks: reading them is [sync]'s.
     */
    suspend fun hasNothingWaiting(): Boolean = mutex.withLock {
        val listed = listMessages()

        listed is Result.Success && listed.data.messages.isEmpty()
    }

    private suspend fun syncLocked(): EmptyResult<DirectMessageSyncError> {
        val restored = restoreContacts()

        if (restored is Result.Error) return Result.Error(DirectMessageSyncError.Contacts(restored.error))

        val messages = when (val list = listMessages()) {
            is Result.Success -> list.data.messages
            is Result.Error -> return Result.Error(DirectMessageSyncError.Network(list.error))
        }

        // Most calls find nothing, and opening the MLS client is not free.
        if (messages.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(DirectMessageSyncError.Identity(opened.error))
        }

        val settled = mutableListOf<String>()
        var failure: DirectMessageSyncError? = null

        // A sender whose message has to be read again is left alone for the rest of this
        // pass: acted on out of order, an older Welcome of theirs would be adopted after a
        // newer one, and the channel would end up in a group they have already left.
        val deferredSenders = mutableSetOf<String>()

        // The newest group joined from each sender's Welcomes in this pass, see [join].
        val joinedGroups = LinkedHashMap<String, ByteArray>()

        try {
            messages.forEach { message ->
                if (message.senderUserUuid in deferredSenders) return@forEach

                // A pass stops between messages, never inside one. Reading a message spends its
                // key, so one cancelled after its read and before what it said was stored could
                // never be read again, and the next pass would acknowledge it as unreadable.
                currentCoroutineContext().ensureActive()

                val delivery = withContext(NonCancellable) {
                    deliver(client = client, message = message, joinedGroups = joinedGroups)
                }

                when (delivery) {
                    Delivery.Settled -> settled += message.uuid

                    is Delivery.Retry -> {
                        deferredSenders += message.senderUserUuid
                        failure = failure ?: delivery.error
                    }
                }
            }
        } finally {
            // Whether or not the pass got to its end, for the same reason: what the handlers
            // kept for it came from messages already spent. Before the answers below, which
            // draw on the same budgets of replies and come second to these.
            withContext(NonCancellable) {
                handlers.forEach { it.finishPass() }
            }
        }

        // One answer per peer, in the group they hold now, and none while anything else this
        // device sent them because of what they sent is still on their budget, see
        // [DirectChannelRepository.claimReply]. Its result is ignored, see [ChannelJoined].
        // Not cancellable, as the deliveries were: the Welcome is never joined again, so an
        // answer dropped here would not come back with the next pass.
        withContext(NonCancellable) {
            joinedGroups.forEach { (peerUuid, mlsGroupId) ->
                if (claimReply(peerUuid, Reply.CHANNEL_JOINED)) {
                    replyIn(peerUuid, mlsGroupId, ChannelJoined.serialize())
                }
            }
        }

        // An acknowledgement that does not go through hands the same messages back next
        // time. Reading one again fails, its key is gone, so it is acknowledged then, and
        // what it said is already stored.
        settled.chunked(MAX_ACKNOWLEDGEMENT_BATCH).forEach { batch ->
            val acknowledgement = acknowledge(AcknowledgeDirectMessagesBody(messageUuids = batch))

            if (acknowledgement is Result.Error) {
                return Result.Error(DirectMessageSyncError.Network(acknowledgement.error))
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Decrypts one message and offers it around, joining the group it came in when the
     * sender had to build a new one.
     *
     * [Delivery.Settled] covers a message acted on, one no handler wanted and one that can
     * never be read: none is worth keeping, so all are acknowledged. So is one from a blocked
     * sender, see [database.data.local.entity.IdentityConflictEntity], left unread: only a
     * log-out lifts the block, and the groups it could be read in go with it. And one from somebody
     * the user blocked, see [database.data.local.entity.BlockedUserEntity], whatever it says, which
     * is what blocking them is for: their answer to a request of the user's, a key, a notice.
     */
    private suspend fun deliver(
        client: MlsClient,
        message: DirectMessageDto,
        joinedGroups: MutableMap<String, ByteArray>,
    ): Delivery {
        // The server's word for the sender is enough: nothing is acted on unless the one the
        // message itself names is the same.
        if (isBlocked(message.senderUserUuid)) {
            return Delivery.Settled
        }

        val envelope = DirectMessageEnvelope.parse(ciphertext = message.ciphertext)
            ?: return Delivery.Settled

        // In the group of a request the user accepted, an acceptance whose answer never came
        // back is completed first, once the server says it took the request, which pins the
        // sender: the message is then read as theirs. It finds none in one of the user's groups,
        // whose id no request can have.
        val completion = completeAcceptanceIn(client, message.senderUserUuid, envelope.mlsGroupId)

        if (completion is Result.Error) {
            return Delivery.Retry(error = DirectMessageSyncError.Contacts(error = completion.error))
        }

        // Checked and read under the one lock: a group being joined is held by the library before
        // the database names it, see [withGroupJoinLock].
        var reading = withGroupJoinLock {
            // Left unread, whatever it says, in a group the user is no longer in too: read, it
            // would be offered as a direct message and make the group the channel to its sender,
            // see [DirectMessageSync]. A group's members talk there by other means, which the group
            // sync reads, see [group.GroupSync].
            if (isGroup(envelope.mlsGroupId)) {
                null
            } else {
                client.readApplicationMessage(mlsGroupId = envelope.mlsGroupId, message = envelope.message)
            }
        } ?: return Delivery.Settled

        var mlsGroupId = envelope.mlsGroupId

        if (reading is ApplicationMessageReading.Unreadable && envelope.welcome != null) {
            val joined = try {
                join(client = client, message = message, welcome = envelope.welcome, joinedGroups = joinedGroups)
            } catch (exception: MlsException) {
                // A Welcome that was already used, was never ours, is for a group already
                // joined, or seats anybody but the sender, is simply not a way in. Storage
                // failing says nothing about the Welcome, so that one is tried again.
                return if (exception.isRetryable()) {
                    Delivery.Retry(error = DirectMessageSyncError.Storage)
                } else {
                    Delivery.Settled
                }
            } ?: return Delivery.Settled

            mlsGroupId = joined

            reading = client.readApplicationMessage(
                mlsGroupId = joined,
                message = envelope.message,
            )
        }

        return when (reading) {
            ApplicationMessageReading.Retry -> Delivery.Retry(error = DirectMessageSyncError.Storage)

            ApplicationMessageReading.Unreadable -> Delivery.Settled

            is ApplicationMessageReading.Read -> {
                // The server says who sent it and MLS proves it. A server that named
                // somebody else was either mistaken or trying something, and either way
                // there is nothing here worth acting on.
                if (reading.senderUserUuid != message.senderUserUuid) {
                    return Delivery.Settled
                }

                // Only somebody this device chose to trust is acted on: the sender of a
                // request the user has not accepted, or whose acceptance the server never
                // took, was never pinned, and must not become somebody this device knows,
                // which the next Welcome from them would pin.
                if (!reading.isSenderPinned) return Delivery.Settled

                // Pinned is still not a contact: whoever the user sent a request to is pinned
                // before they answer it. So the channel and each handler go by what the sender
                // is to the user, see [DirectChannelRepository.noteReadIn].
                noteReadIn(client, reading.senderUserUuid, mlsGroupId)

                offer(reading = reading, mlsGroupId = mlsGroupId)

                Delivery.Settled
            }
        }
    }

    /**
     * Joins the group [welcome] was sealed for, records it as the channel to the message's
     * sender, and notes it in [joinedGroups] for the pass to tell them, when their budget of
     * replies allows, that this device is in, see [ChannelJoined]. Returns the group's id, or
     * null when the sender is nobody this device knows, and throws [MlsException] when the
     * join fails.
     *
     * The Welcome is acted on only for somebody this device already knows: joining stores a
     * group and pins the sender's MLS identity, which is not something to do because an
     * unknown name on a server-supplied message asked for it. The join itself refuses any
     * group but one holding exactly the user and that sender, which is what makes it safe
     * to record as their channel before anything has been read in it.
     *
     * The group is taken from what the join returned, never from what the envelope claimed:
     * the envelope's is a field the sender wrote and nothing authenticates.
     */
    private suspend fun join(
        client: MlsClient,
        message: DirectMessageDto,
        welcome: ByteArray,
        joinedGroups: MutableMap<String, ByteArray>,
    ): ByteArray? {
        if (!isKnownPeer(message.senderUserUuid)) return null

        val joined = client.joinGroupFrom(
            welcome = welcome,
            peerUuid = message.senderUserUuid,
            pinPeer = true,
        )

        adopt(client, message.senderUserUuid, joined)

        // Told once the pass is over, in the newest group joined: messages come oldest first,
        // and a peer who rebuilt while this device was away holds the last one.
        joinedGroups[message.senderUserUuid] = joined

        return joined
    }

    /**
     * Offers a decrypted message to each handler until one claims it.
     *
     * One nobody claims is a [ChannelJoined], whose reading is all it is for, or something
     * from a build that knows what this one does not. It is acknowledged rather than kept
     * either way: this device will not understand it any better next time.
     */
    private suspend fun offer(
        reading: ApplicationMessageReading.Read,
        mlsGroupId: ByteArray,
    ) {
        handlers.forEach { handler ->
            val handling = handler.handle(
                senderUserUuid = reading.senderUserUuid,
                mlsGroupId = mlsGroupId,
                payload = reading.payload,
            )

            if (handling == DirectMessageHandling.HANDLED) return
        }
    }
}

/** What became of one message, see [DirectMessageSync]. */
private sealed interface Delivery {
    /** Acted on, or never going to be. Either way it is acknowledged. */
    data object Settled : Delivery

    /**
     * Not read, for [error]: the device's own storage failed before it could be, or the
     * server could not be asked what reading it needs. It is left on the server to be read
     * again.
     */
    class Retry(val error: DirectMessageSyncError) : Delivery
}
