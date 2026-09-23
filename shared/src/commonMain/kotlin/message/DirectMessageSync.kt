package message

import channel.DirectChannelRepository
import database.data.remote.AcknowledgeDirectMessagesBody
import database.data.remote.DirectMessageApi
import database.data.remote.DirectMessageDto
import database.data.remote.MAX_ACKNOWLEDGEMENT_BATCH
import mls.ApplicationMessageReading
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.isRetryable
import mls.joinGroupFrom
import mls.readApplicationMessage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.Result

/**
 * Takes in the messages waiting for the user and hands each to whichever handler it
 * belongs to.
 *
 * One place decrypts, because decrypting is the only way to find out what a message is:
 * the server stores an opaque string, and what is inside it, a contact card, a notice that
 * somebody removed the user, the key to an expense or a request for one, is only knowable
 * afterwards. Reading also *consumes* the message, MLS deletes the key that opened it, so a
 * message may be read exactly once and whatever it said has to be acted on there and then.
 *
 * Every message that was read, and every message that never will be, is acknowledged,
 * which is the only thing that deletes it server-side. The exception is a message the
 * device could not store the effects of: that one is left alone, since nothing was
 * consumed and the server will hand it back.
 */
class DirectMessageSync internal constructor(
    private val directMessageApi: DirectMessageApi,
    private val directChannelRepository: DirectChannelRepository,
    private val mlsIdentityRepository: MlsIdentityRepository,
    private val handlers: List<DirectMessageHandler>,
) {

    suspend fun sync(): EmptyResult<DirectMessageSyncError> {
        val messages = when (val list = directMessageApi.getDirectMessages()) {
            is Result.Success -> list.data.messages
            is Result.Error -> return Result.Error(DirectMessageSyncError.Network(list.error))
        }

        // Most calls find nothing, and opening the MLS client is not free.
        if (messages.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(DirectMessageSyncError.Identity(opened.error))
        }

        val settled = mutableListOf<String>()
        var failure: DirectMessageSyncError? = null

        messages.forEach { message ->
            when (deliver(client = client, message = message)) {
                DirectMessageHandling.HANDLED, DirectMessageHandling.NOT_MINE -> settled += message.uuid

                DirectMessageHandling.RETRY -> failure = failure ?: DirectMessageSyncError.Storage
            }
        }

        // An acknowledgement that does not go through hands the same messages back next
        // time. Reading one again fails, its key is gone, so it is acknowledged then, and
        // what it said is already stored.
        settled.chunked(MAX_ACKNOWLEDGEMENT_BATCH).forEach { batch ->
            val acknowledgement = directMessageApi.acknowledgeDirectMessages(
                body = AcknowledgeDirectMessagesBody(messageUuids = batch),
            )

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
     * [DirectMessageHandling.NOT_MINE] covers both a message no handler wanted and one that
     * can never be read: neither is worth keeping, so both are acknowledged.
     */
    private suspend fun deliver(
        client: MlsClient,
        message: DirectMessageDto,
    ): DirectMessageHandling {
        val envelope = DirectMessageEnvelope.parse(ciphertext = message.ciphertext)
            ?: return DirectMessageHandling.NOT_MINE

        var mlsGroupId = envelope.mlsGroupId

        var reading = client.readApplicationMessage(
            mlsGroupId = mlsGroupId,
            message = envelope.message,
        )

        if (reading is ApplicationMessageReading.Unreadable && envelope.welcome != null) {
            val joined = join(client = client, message = message, envelope = envelope)
                ?: return DirectMessageHandling.NOT_MINE

            mlsGroupId = joined

            reading = client.readApplicationMessage(
                mlsGroupId = joined,
                message = envelope.message,
            )
        }

        return when (reading) {
            ApplicationMessageReading.Retry -> DirectMessageHandling.RETRY

            ApplicationMessageReading.Unreadable -> DirectMessageHandling.NOT_MINE

            is ApplicationMessageReading.Read -> {
                // The server says who sent it and MLS proves it. A server that named
                // somebody else was either mistaken or trying something, and either way
                // there is nothing here worth acting on.
                if (reading.senderUserUuid != message.senderUserUuid) {
                    return DirectMessageHandling.NOT_MINE
                }

                directChannelRepository.noteReadIn(
                    client = client,
                    peerUuid = reading.senderUserUuid,
                    mlsGroupId = mlsGroupId,
                )

                offer(reading = reading, mlsGroupId = mlsGroupId)
            }
        }
    }

    /**
     * Joins the group a Welcome was sealed for, and records it as the channel to its
     * sender. Returns the group's id, or null when it was not joined.
     *
     * The Welcome is acted on only for somebody this device already knows: joining stores a
     * group and pins whatever MLS identity it carries, which is not something to do because
     * an unknown name on a server-supplied message asked for it.
     *
     * The group is taken from what the join returned, never from what the envelope claimed:
     * the envelope's is a field the sender wrote and nothing authenticates.
     */
    private suspend fun join(
        client: MlsClient,
        message: DirectMessageDto,
        envelope: DirectMessageEnvelope,
    ): ByteArray? {
        val welcome = envelope.welcome ?: return null

        if (!directChannelRepository.isKnownPeer(peerUuid = message.senderUserUuid)) return null

        val joined = try {
            client.joinGroupFrom(welcome = welcome)
        } catch (exception: MlsException) {
            // A Welcome that was already used, or was never ours, is simply not a way in.
            // Storage failing is reported by the read that follows, which finds no group.
            if (exception.isRetryable()) return null

            return null
        }

        directChannelRepository.adopt(peerUuid = message.senderUserUuid, mlsGroupId = joined)

        return joined
    }

    /** Offers a decrypted message to each handler until one claims it. */
    private suspend fun offer(
        reading: ApplicationMessageReading.Read,
        mlsGroupId: ByteArray,
    ): DirectMessageHandling {
        handlers.forEach { handler ->
            val handling = handler.handle(
                senderUserUuid = reading.senderUserUuid,
                mlsGroupId = mlsGroupId,
                payload = reading.payload,
            )

            if (handling != DirectMessageHandling.NOT_MINE) return handling
        }

        // From a build that knows something this one does not. Acknowledged rather than
        // kept: this device will not understand it any better next time.
        return DirectMessageHandling.NOT_MINE
    }
}
