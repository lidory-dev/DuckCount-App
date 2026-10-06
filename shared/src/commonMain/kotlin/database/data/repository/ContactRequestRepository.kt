package database.data.repository

import app.lidory.duckcount.hashDiscoveryUsername
import authenticationUtils.getAuthenticatedUserUuid
import contact.AnswerContactRequestError
import channel.DirectChannelRepository
import contact.ContactCard
import message.DirectMessageEnvelope
import contact.ContactRequestEnvelope
import contact.ContactSyncError
import contact.SendContactRequestError
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.OpenedContactRequest
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.mappers.toContactRequest
import database.data.mappers.toContactRequestEntity
import database.data.remote.AcceptContactRequestBody
import database.data.remote.ContactApi
import database.data.remote.SendContactRequestBody
import database.domain.ContactRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mls.ApplicationMessageReading
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.isRetryable
import mls.joinGroupFrom
import mls.pinPeerIn
import mls.readApplicationMessage
import mls.verifyPeerIn
import profile.ProfileError
import profile.ProfileRepository
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import util.onError
import vault.VaultRepository
import kotlin.io.encoding.Base64

/**
 * The user's contact requests: the ones addressed to them, and the ones they send.
 *
 * For the incoming ones the server is the only source, and the local table follows it.
 * What the table adds is what opening a request yields: the group it created, and the key
 * to its sender's profile. Opening one joins that group, and a group is only ever joined
 * once, so that can only ever happen once. It also records that the user's acceptance went
 * out, see [ContactRequestEntity.isAcceptanceSent], which the server keeps no trace of: a
 * request so marked that vanishes is completed, its sender pinned and made a contact,
 * rather than dropped.
 */
class ContactRequestRepository(
    private val contactRequestDao: ContactRequestDao,
    private val identityConflictDao: IdentityConflictDao,
    private val contactApi: ContactApi,
    private val vaultRepository: VaultRepository,
    private val mlsIdentityRepository: MlsIdentityRepository,
    private val profileRepository: ProfileRepository,
    private val contactRepository: ContactRepository,
    private val directChannelRepository: DirectChannelRepository,
) {
    /**
     * Serialises the operations on the requests addressed to the user. Each one reads the
     * local table and writes it back after a round trip, and a refresh running alongside an
     * acceptance would otherwise drop the request, and delete its group, from under it.
     *
     * The state of the groups they carry is not what this guards: the MLS layer locks that
     * per group, for every caller.
     */
    private val mutex = Mutex()

    /**
     * The requests this process completed, see [completeAcceptance], for an answer to one of
     * them still on its way: a screen can offer a request a refresh is completing, and the
     * tap then waits for the lock. Only touched under [mutex].
     */
    private val completedUuids = HashSet<String>()

    /** How many requests are waiting for the user's answer. */
    fun getPendingCount(): Flow<Int> {
        return contactRequestDao.getCountAsFlow()
    }

    /** The requests waiting for the user's answer, oldest first. */
    fun getPending(): Flow<List<ContactRequest>> {
        return contactRequestDao.getOpenedAsFlow().map { requests ->
            requests.map { it.toContactRequest() }
        }
    }

    /**
     * Brings the requests addressed to the user in line with the server, opens the new
     * ones and reads their senders' names.
     *
     * A request that can never be opened is refused on the spot, without the user ever
     * seeing it. Typically it was sent to a key package whose secret a log-out or a
     * reinstall took along. Its sender is not told, and is free to send a new one, which a
     * request left pending would prevent.
     *
     * A request the user accepted that the server no longer lists is completed: its sender
     * is pinned and becomes a contact, see [ContactRequestEntity.isAcceptanceSent]. It stays
     * until that can be done.
     *
     * Every step is attempted even when one fails, and the first failure is reported:
     * whatever did not get done is picked up by the next refresh.
     */
    suspend fun refresh(): EmptyResult<ContactSyncError> = mutex.withLock { refreshLocked() }

    /**
     * Whether every request the server lists for the user has been opened on this device.
     * One still to be opened needs the secret of the key package its Welcome was sealed to,
     * see [mls.KeyPackageRepository]. False when the server could not be asked.
     *
     * Only looks: opening them is [refresh]'s.
     */
    suspend fun hasOpenedEveryRequest(): Boolean = mutex.withLock {
        val listed = when (val response = contactApi.getContactRequests()) {
            is Result.Success -> response.data.requests
            is Result.Error -> return@withLock false
        }

        val opened = contactRequestDao.getAll()
            .filter { it.mlsGroupId != null }
            .mapTo(HashSet()) { it.uuid }

        listed.all { it.uuid in opened }
    }

    /**
     * Accepts a request: hands its sender the user's own profile encryption key, and makes
     * them a contact, pinning the MLS identity they hold in the request's group.
     *
     * Pinned only once the server has taken the answer: never when the request is opened,
     * which happens to every request the server lists, from anyone, nor when the answer goes
     * out. A request the user refuses or never answers, or an answer the server never takes,
     * must not decide who its sender is. The identity is checked before the key goes out,
     * against any already pinned for them.
     *
     * The key travels encrypted to the group the request created, and the server delivers
     * it to the sender as a direct message. The sender becomes a contact on this device once
     * the server has taken the answer, and reaches the vault with the next
     * [ContactRepository.backUp].
     *
     * An answer lost on its way back does not lose the sender: the request is marked before
     * the acceptance goes out, see [ContactRequestEntity.isAcceptanceSent], and completed
     * when it turns out gone, by the next [refresh], by accepting it again, or by reading a
     * message from them in its group, see [completeAcceptanceIn].
     */
    suspend fun accept(requestUuid: String): EmptyResult<AnswerContactRequestError> =
        mutex.withLock { acceptLocked(requestUuid = requestUuid) }

    /**
     * Refuses a request, deleting it along with the group it created. Its sender is not
     * told, and may send another one.
     *
     * A request the user accepted before without hearing back, and that has gone since, most
     * likely went because the server took that acceptance: its sender is made a contact
     * instead, see [AnswerContactRequestError.AlreadyAccepted].
     */
    suspend fun refuse(requestUuid: String): EmptyResult<AnswerContactRequestError> =
        mutex.withLock { refuseLocked(requestUuid = requestUuid) }

    /**
     * Asks the holder of the discovery [username] to become a contact, handing them the
     * user's profile encryption key.
     *
     * The request creates the MLS group the two of them will share. The lookup hands over
     * one of the recipient's key packages, the group is created with them in it, and the
     * key travels encrypted to that group next to the Welcome that lets them join it.
     *
     * The request is recorded with its group before it goes out, see
     * [OutgoingContactRequestEntity]: only its answer, the recipient's card read in that
     * group, makes them a contact. A request the server provably did not store deletes both
     * again. Any other failure keeps the group, and its row once written, whether the answer
     * was lost, timed out or the caller went away: the request may be waiting for the
     * recipient all the same, their answer can only be read in that group, and sending again
     * finds it pending.
     */
    suspend fun sendContactRequest(username: String): EmptyResult<SendContactRequestError> {
        val digest = hashDiscoveryUsername(username = username)
            ?: return Result.Error(SendContactRequestError.InvalidUsername)

        // Before the lookup spends one of the recipient's key packages: until the contacts
        // are back, one of them looks like a stranger, and creating the group would pin
        // them from that key package ahead of the identity the vault holds for them.
        val restored = contactRepository.restore()

        if (restored is Result.Error) return Result.Error(SendContactRequestError.Contacts(restored.error))

        val recipient = when (val lookup = contactApi.lookupContact(username = digest)) {
            is Result.Success -> lookup.data
            is Result.Error -> return Result.Error(lookup.error.toSendContactRequestError())
        }

        // The server only refuses a request to oneself once it is sent, after the lookup
        // has already spent one of the user's own key packages. Nothing more goes on it.
        if (recipient.userUuid == getAuthenticatedUserUuid()) {
            return Result.Error(SendContactRequestError.Self)
        }

        if (contactRepository.isContact(userUuid = recipient.userUuid)) {
            return Result.Error(SendContactRequestError.AlreadyContact)
        }

        // The server stores key packages without reading them, so this is whatever the
        // recipient's client published.
        val keyPackage = runCatching { Base64.Default.decode(recipient.keyPackage) }.getOrNull()
            ?: return Result.Error(SendContactRequestError.Crypto)

        // Everything that can fail without leaving anything behind comes first. Once the group
        // exists, a failure the library reports deletes it again, and the caller going away
        // leaves it behind, harmlessly: its Welcome never left the device.
        val profileEncryptionKey = when (val read = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(SendContactRequestError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(SendContactRequestError.Identity(opened.error))
        }

        // A key package matching the identity this device pinned for them would go through,
        // when it is the one the vault disagrees with. See IdentityConflictEntity.
        if (identityConflictDao.isBlocked(peerUuid = recipient.userUuid)) {
            return Result.Error(SendContactRequestError.IdentityChanged)
        }

        val group = try {
            withContext(Dispatchers.IO) {
                client.createGroup(
                    invitees = listOf(
                        InviteeKeyPackage(userUuid = recipient.userUuid, keyPackage = keyPackage),
                    ),
                )
            }
        } catch (_: MlsException.IdentityKeyMismatch) {
            return Result.Error(SendContactRequestError.IdentityChanged)
        } catch (_: MlsException) {
            return Result.Error(SendContactRequestError.Crypto)
        }

        val envelope = try {
            val message = client.encryptApplicationMessage(
                mlsGroupId = group.mlsGroupId,
                payload = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize(),
            )

            // One invitee, so one Welcome: the library fails the creation rather than leave
            // an invitee without theirs.
            ContactRequestEnvelope(welcome = group.welcomes.single().welcome, message = message)
        } catch (_: MlsException) {
            client.deleteGroupQuietly(mlsGroupId = group.mlsGroupId)

            return Result.Error(SendContactRequestError.Crypto)
        }

        // Before the request goes out, so that no answer can arrive with nothing to match it.
        val outgoing = OutgoingContactRequestEntity(
            mlsGroupId = group.mlsGroupId,
            recipientUserUuid = recipient.userUuid,
        )

        contactRequestDao.insertOutgoing(request = outgoing)

        val send = contactApi.sendContactRequest(
            body = SendContactRequestBody(
                recipientUserUuid = recipient.userUuid,
                ciphertext = envelope.serialize(),
            ),
        )

        if (send is Result.Error) {
            if (send.error in REQUEST_NEVER_STORED) {
                contactRequestDao.deleteOutgoing(
                    recipientUserUuid = outgoing.recipientUserUuid,
                    mlsGroupId = outgoing.mlsGroupId,
                )
                client.deleteGroupQuietly(mlsGroupId = group.mlsGroupId)
            }

            return Result.Error(send.error.toSendContactRequestError())
        }

        return Result.Success(Unit)
    }

    /**
     * Makes [senderUserUuid] a contact on the strength of [card], when it answers a request the
     * user sent them: read in [mlsGroupId], the group that request created. Any other card is
     * ignored, since its sender would otherwise become a contact the user never asked for.
     */
    internal suspend fun receiveAnswer(senderUserUuid: String, mlsGroupId: ByteArray, card: ContactCard) {
        val requests = contactRequestDao.deleteOutgoing(
            recipientUserUuid = senderUserUuid,
            mlsGroupId = mlsGroupId,
        )

        if (requests == 0) return

        contactRequestDao.deleteOutgoingTo(recipientUserUuid = senderUserUuid)

        contactRepository.add(
            userUuid = senderUserUuid,
            profileEncryptionKey = card.profileEncryptionKey,
            fullName = null,
        )
    }

    /**
     * Completes the user's acceptance of the request [senderUserUuid] sent with [mlsGroupId],
     * on a message from them in that group, before it is read. Fails when the message has to
     * wait on the server for a later pass: the server could not be asked, or pinning them
     * failed in a way that trying again can fix.
     *
     * Before the read, which only trusts them once pinned, and accepting did not pin them:
     * read first, the message would be lost to a check that could not be made. Before it is
     * acted on too, which may need them as a contact: a notice that they removed the user
     * would otherwise find nobody to remove, and the next [refresh] would add them back.
     *
     * The message is only the occasion. An honest sender only sends in that group once they
     * have read the card the acceptance carried, but anybody can send in a group they created:
     * what says the server took the acceptance is that it no longer lists the request, as for
     * [refresh], see [ContactRequestEntity.isAcceptanceSent].
     */
    internal suspend fun completeAcceptanceIn(
        client: MlsClient,
        senderUserUuid: String,
        mlsGroupId: ByteArray,
    ): EmptyResult<ContactSyncError> {
        // Read without the lock first, so that every other message goes on without waiting for
        // an answer or a refresh in flight. The mark is written before the acceptance goes out,
        // so it is there before anything the sender can send after reading it.
        contactRequestDao.getAccepted(senderUserUuid = senderUserUuid, mlsGroupId = mlsGroupId)
            ?: return Result.Success(Unit)

        return mutex.withLock {
            // Completed meanwhile, its sender pinned before the row went, or dropped.
            val accepted = contactRequestDao.getAccepted(senderUserUuid = senderUserUuid, mlsGroupId = mlsGroupId)
                ?: return@withLock Result.Success(Unit)

            when (val listed = isListed(requestUuid = accepted.uuid)) {
                is Result.Error -> Result.Error(ContactSyncError.Network(listed.error))

                // Still listed, so not taken, whatever the message says: it is read as it
                // stands, which acts on its sender only if pinned already.
                is Result.Success -> when {
                    listed.data -> Result.Success(Unit)

                    completeAcceptance(client = client, request = accepted) == Completion.RETRY ->
                        Result.Error(ContactSyncError.Storage)

                    else -> Result.Success(Unit)
                }
            }
        }
    }

    private suspend fun refreshLocked(): EmptyResult<ContactSyncError> {
        val listed = when (val response = contactApi.getContactRequests()) {
            is Result.Success -> response.data.requests.map { it.toContactRequestEntity() }
            is Result.Error -> return Result.Error(ContactSyncError.Network(response.error))
        }

        val listedUuids = listed.mapTo(HashSet()) { it.uuid }

        var failure: ContactSyncError? = null

        // Most likely gone because the server took the user's acceptance, whose answer never
        // made it back. See ContactRequestEntity.isAcceptanceSent.
        val accepted = contactRequestDao.getOpenedOnce().filter { it.isAcceptanceSent && it.uuid !in listedUuids }

        if (accepted.isNotEmpty()) {
            when (val opened = mlsIdentityRepository.client()) {
                is Result.Error -> failure = ContactSyncError.Identity(opened.error)

                is Result.Success -> for (request in accepted) {
                    if (completeAcceptance(client = opened.data, request = request) == Completion.RETRY) {
                        failure = failure ?: ContactSyncError.Storage
                    }
                }
            }
        }

        // One the user accepted is completed or dropped, never let go of here: one still there
        // could not be completed yet, and the next refresh tries again.
        val vanished = contactRequestDao.getAll().filter { it.uuid !in listedUuids && !it.isAcceptanceSent }

        contactRequestDao.reconcile(vanishedUuids = vanished.map { it.uuid }, listed = listed)

        // A request vanishes when its sender deletes their account, or accepts the user's
        // own request to them, which clears this one. Either way its group is of no use.
        val abandonedGroupIds = vanished.mapNotNull { it.mlsGroupId }
        val unopened = contactRequestDao.getUnopened()

        // Opening the MLS client is not free, and most refreshes leave it nothing to do.
        if (abandonedGroupIds.isNotEmpty() || unopened.isNotEmpty()) {
            when (val opened = mlsIdentityRepository.client()) {
                is Result.Error -> failure = failure ?: ContactSyncError.Identity(opened.error)

                is Result.Success -> {
                    abandonedGroupIds.forEach { opened.data.deleteGroupQuietly(mlsGroupId = it) }

                    for (request in unopened) {
                        // A refresh stops between requests, never inside one, see [join].
                        currentCoroutineContext().ensureActive()

                        open(client = opened.data, request = request).onError { failure = failure ?: it }
                    }
                }
            }
        }

        nameSenders().onError { failure = failure ?: it }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /** Opens [request], or refuses it when it can never be opened. */
    private suspend fun open(client: MlsClient, request: ContactRequestEntity): EmptyResult<ContactSyncError> {
        val envelope = ContactRequestEnvelope.parse(ciphertext = request.ciphertext)
            ?: return refuseUnreadable(requestUuid = request.uuid)

        return when (join(client = client, request = request, envelope = envelope)) {
            Opening.OPENED -> Result.Success(Unit)
            Opening.RETRY -> Result.Error(ContactSyncError.Storage)
            Opening.UNREADABLE -> refuseUnreadable(requestUuid = request.uuid)
        }
    }

    /**
     * Joins the group [request] created, reads the card it carries and records the request as
     * opened.
     *
     * Not cancellable: once the join has run, the same Welcome is refused ever after, the group
     * being recorded as held and a one-time key package spent. Left unmarked, the request would
     * be tried again by the next refresh, fail, and be refused as unreadable, its sender never
     * told.
     */
    private suspend fun join(
        client: MlsClient,
        request: ContactRequestEntity,
        envelope: ContactRequestEnvelope,
    ): Opening = withContext(NonCancellable) {
        // The join refuses any group but one holding exactly the user and the sender the
        // server authenticated, and nothing read in it can change that, see
        // [readApplicationMessage], so every message read in it, the card included, is theirs.
        // It pins nobody: that waits for the server to take the user's acceptance.
        val mlsGroupId = try {
            client.joinGroupFrom(
                welcome = envelope.welcome,
                peerUuid = request.senderUserUuid,
                pinPeer = false,
            )
        } catch (exception: MlsException) {
            return@withContext if (exception.isRetryable()) Opening.RETRY else Opening.UNREADABLE
        }

        // A group is only ever joined once, so from here on a request that cannot be read
        // now never will be, whatever the reason.
        val reading = client.readApplicationMessage(mlsGroupId = mlsGroupId, message = envelope.message)

        val card = (reading as? ApplicationMessageReading.Read)
            ?.let { ContactCard.parse(plaintext = it.payload) }

        if (card == null) {
            client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

            return@withContext Opening.UNREADABLE
        }

        contactRequestDao.markOpened(
            uuid = request.uuid,
            mlsGroupId = mlsGroupId,
            profileEncryptionKey = card.profileEncryptionKey,
        )

        Opening.OPENED
    }

    /**
     * Reads the name of every sender this device has none for yet.
     *
     * Only a profile that could not be asked for is reported, see [ContactSyncError.Profile].
     */
    private suspend fun nameSenders(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        for (request in contactRequestDao.getOpenedWithoutName()) {
            val read = profileRepository.getFullName(
                userUuid = request.senderUserUuid,
                profileEncryptionKey = request.profileEncryptionKey,
            )

            when (read) {
                is Result.Success -> contactRequestDao.setSenderFullName(
                    uuid = request.uuid,
                    senderFullName = read.data,
                )

                is Result.Error -> if (read.error is ProfileError.Network) {
                    failure = failure ?: ContactSyncError.Profile(read.error)
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    private suspend fun acceptLocked(requestUuid: String): EmptyResult<AnswerContactRequestError> {
        // Completed while the screen still offered it, see completedUuids.
        if (requestUuid in completedUuids) return Result.Success(Unit)

        // Only opened requests are shown, but a refresh can drop one from under the screen.
        val request = contactRequestDao.getOpened(uuid = requestUuid)
            ?: return Result.Error(AnswerContactRequestError.NotFound)

        // The sender may be a contact whose pin the vault holds, which their identity is
        // checked against below.
        val restored = contactRepository.restore()

        if (restored is Result.Error) return Result.Error(AnswerContactRequestError.Contacts(restored.error))

        val profileEncryptionKey = when (val read = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(AnswerContactRequestError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(AnswerContactRequestError.Identity(opened.error))
        }

        // The check below would pass the identity this device holds, when it is the one the
        // vault disagrees with. See IdentityConflictEntity.
        if (identityConflictDao.isBlocked(peerUuid = request.senderUserUuid)) {
            return Result.Error(AnswerContactRequestError.IdentityChanged)
        }

        val message = try {
            // Checked, not pinned: that waits for the server to take the acceptance.
            client.verifyPeerIn(mlsGroupId = request.mlsGroupId, peerUuid = request.senderUserUuid)

            client.encryptApplicationMessage(
                mlsGroupId = request.mlsGroupId,
                payload = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize(),
            )
        } catch (_: MlsException.IdentityKeyMismatch) {
            return Result.Error(AnswerContactRequestError.IdentityChanged)
        } catch (_: MlsException) {
            return Result.Error(AnswerContactRequestError.Crypto)
        }

        // Before it goes out: once the server takes it, the request is gone, and an answer
        // lost on the way back would leave nothing saying that it was taken.
        contactRequestDao.markAcceptanceSent(uuid = request.uuid)

        val acceptance = contactApi.acceptContactRequest(
            requestUuid = request.uuid,
            body = AcceptContactRequestBody(
                ciphertext = DirectMessageEnvelope(
                    mlsGroupId = request.mlsGroupId,
                    message = message,
                ).serialize(),
            ),
        )

        if (acceptance is Result.Error) {
            // Provably not taken, so the mark goes again, unless an earlier attempt set it.
            if (!request.isAcceptanceSent && acceptance.error in ACCEPTANCE_NEVER_TAKEN) {
                withContext(NonCancellable) { contactRequestDao.clearAcceptanceSent(uuid = request.uuid) }
            }

            if (acceptance.error != NetworkError.NOT_FOUND) {
                return Result.Error(AnswerContactRequestError.Network(acceptance.error))
            }

            val listed = when (val listing = isListed(requestUuid = request.uuid)) {
                is Result.Success -> listing.data
                is Result.Error -> return Result.Error(AnswerContactRequestError.Network(listing.error))
            }

            // The proxy's 404, then: the request is still there for a new attempt to take.
            if (listed) return Result.Error(AnswerContactRequestError.Network(acceptance.error))

            // Read before this attempt marked it: an earlier acceptance went out, and is most
            // likely what consumed the request.
            if (!request.isAcceptanceSent) {
                forget(requestUuid = request.uuid)

                return Result.Error(AnswerContactRequestError.NotFound)
            }
        }

        return when (completeAcceptance(client = client, request = request)) {
            // Taken all the same, and completed by the next refresh.
            Completion.COMPLETED, Completion.RETRY -> Result.Success(Unit)

            Completion.DROPPED -> Result.Error(AnswerContactRequestError.IdentityChanged)
        }
    }

    private suspend fun refuseLocked(requestUuid: String): EmptyResult<AnswerContactRequestError> {
        // Completed while the screen still offered it, see completedUuids: too late to refuse.
        if (requestUuid in completedUuids) return Result.Error(AnswerContactRequestError.AlreadyAccepted)

        val refusal = contactApi.refuseContactRequest(requestUuid = requestUuid)

        if (refusal is Result.Error) {
            if (refusal.error != NetworkError.NOT_FOUND) {
                return Result.Error(AnswerContactRequestError.Network(refusal.error))
            }

            val listed = when (val listing = isListed(requestUuid = requestUuid)) {
                is Result.Success -> listing.data
                is Result.Error -> return Result.Error(AnswerContactRequestError.Network(listing.error))
            }

            // The proxy's 404, then: the request is still there for a new attempt to refuse.
            if (listed) return Result.Error(AnswerContactRequestError.Network(refusal.error))

            // Gone already (its sender deleted their account, or accepted the user's own
            // request to them), which is all refusing it was for. Unless the user accepted it
            // before: then the server most likely took that, and its sender has the user as a
            // contact whatever this device decides now.
            val accepted = contactRequestDao.getOpened(uuid = requestUuid)?.takeIf { it.isAcceptanceSent }

            if (accepted != null) {
                val client = when (val opened = mlsIdentityRepository.client()) {
                    is Result.Success -> opened.data
                    is Result.Error -> return Result.Error(AnswerContactRequestError.Identity(opened.error))
                }

                return when (completeAcceptance(client = client, request = accepted)) {
                    Completion.COMPLETED, Completion.RETRY -> Result.Error(AnswerContactRequestError.AlreadyAccepted)

                    // Dropped instead, which is all refusing it was for.
                    Completion.DROPPED -> Result.Success(Unit)
                }
            }
        }

        forget(requestUuid = requestUuid)

        return Result.Success(Unit)
    }

    /**
     * Makes the sender of [request], whose acceptance the server took, a contact, pinning the
     * identity they hold in its group, and drops the request, recording it in [completedUuids].
     *
     * Not cancellable: leaving the screen would otherwise leave the sender a contact with no
     * channel, until a refresh completed the rest. The row goes last, so that even a process
     * dying halfway leaves the request for the next [refresh] to complete again.
     *
     * The request is dropped instead when they can never be pinned: a different identity was
     * pinned for them since the acceptance went out, by the user's own request to them for one,
     * or the group it created can no longer be used. With two identities, one of them is not
     * theirs, and the one pinned first stands, as everywhere else.
     */
    private suspend fun completeAcceptance(client: MlsClient, request: OpenedContactRequest): Completion =
        withContext(NonCancellable) {
            try {
                client.pinPeerIn(mlsGroupId = request.mlsGroupId, peerUuid = request.senderUserUuid)
            } catch (exception: MlsException) {
                if (exception.isRetryable()) return@withContext Completion.RETRY

                forget(requestUuid = request.uuid)

                return@withContext Completion.DROPPED
            }

            contactRepository.add(
                userUuid = request.senderUserUuid,
                profileEncryptionKey = request.profileEncryptionKey,
                fullName = request.senderFullName,
            )

            // Accepting closed the user's own requests to them as well, server-side.
            contactRequestDao.deleteOutgoingTo(recipientUserUuid = request.senderUserUuid)

            // The group the request created is the channel to them from here on, and this is
            // the only moment its id is known outside the request row about to be deleted.
            directChannelRepository.adopt(
                peerUuid = request.senderUserUuid,
                mlsGroupId = request.mlsGroupId,
            )

            contactRequestDao.delete(uuids = listOf(request.uuid))

            completedUuids += request.uuid

            Completion.COMPLETED
        }

    /**
     * Whether the server still lists [requestUuid], which one of its routes just answered
     * `404` about.
     *
     * That `404` is no proof on its own: the proxy in front of the API answers one for every
     * route while the API is being redeployed. A request dropped on its word would come back
     * with the next refresh and, its group joined already, be refused as unreadable. The
     * listing, which [refresh] goes by, has the last word.
     */
    private suspend fun isListed(requestUuid: String): Result<Boolean, NetworkError> =
        contactApi.getContactRequests().map { response -> response.requests.any { it.uuid == requestUuid } }

    /**
     * Refuses a request that can never be opened, see [refresh]. Never accepted either, since
     * only an opened one can be.
     */
    private suspend fun refuseUnreadable(requestUuid: String): EmptyResult<ContactSyncError> {
        val refusal = contactApi.refuseContactRequest(requestUuid = requestUuid)

        // Not found means the request is gone already, which is all refusing it was for, or the
        // proxy answered while the API was being redeployed: the next refresh then lists it
        // again, fails to open it again, and refuses it again.
        if (refusal is Result.Error && refusal.error != NetworkError.NOT_FOUND) {
            return Result.Error(ContactSyncError.Network(refusal.error))
        }

        forget(requestUuid = requestUuid)

        return Result.Success(Unit)
    }

    /**
     * Drops a request the server no longer holds, along with its group if it was opened.
     *
     * Not cancellable: a row left behind with an acceptance marked on it would be completed by
     * the next refresh, which no longer finds it listed.
     */
    private suspend fun forget(requestUuid: String) = withContext(NonCancellable) {
        val mlsGroupId = contactRequestDao.get(uuid = requestUuid)?.mlsGroupId

        contactRequestDao.delete(uuids = listOf(requestUuid))

        if (mlsGroupId == null) return@withContext

        when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data.deleteGroupQuietly(mlsGroupId = mlsGroupId)

            // The group stays behind. Nothing its sender sends in it is acted on until this
            // device pins them, see DirectMessageSync.
            is Result.Error -> Unit
        }
    }
}

/**
 * The failures that prove an acceptance was not taken: the host never resolved, so nothing
 * was sent; the token was turned away before the handler ran; or a `404`, from the handler
 * finding no request to take, or from the proxy in front of it while the API is being
 * redeployed. Every other failure of the route (a timeout, a dropped connection, a `5xx` the
 * proxy may return after the commit) leaves open that it was taken.
 */
private val ACCEPTANCE_NEVER_TAKEN = setOf(NetworkError.NO_INTERNET, NetworkError.UNAUTHORIZED, NetworkError.NOT_FOUND)

/**
 * The failures that prove a contact request was not stored: the host never resolved, so
 * nothing was sent; the token was turned away before the handler ran; the sender is over
 * their quota, which is checked before anything is written; or the handler wrote nothing,
 * the recipient being unknown (`404`, which the proxy in front of it also answers while the
 * API is being redeployed) or asked already (`409`, about an earlier request, which stays).
 * Every other failure (a timeout, a dropped connection, a `5xx` the proxy may return after
 * the commit) leaves open that it was.
 */
private val REQUEST_NEVER_STORED = setOf(
    NetworkError.NO_INTERNET,
    NetworkError.UNAUTHORIZED,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
)

/** Maps the lookup's and the request's own refusals, the rest being plain network errors. */
private fun NetworkError.toSendContactRequestError(): SendContactRequestError = when (this) {
    NetworkError.NOT_FOUND -> SendContactRequestError.NotFound
    NetworkError.CONFLICT -> SendContactRequestError.AlreadyPending
    NetworkError.TOO_MANY_REQUESTS -> SendContactRequestError.RateLimited
    else -> SendContactRequestError.Network(this)
}

/** How far [ContactRequestRepository] got opening a request. */
private enum class Opening {
    OPENED,

    /** The join failed in a way that trying again can fix, see [mls.isRetryable]. */
    RETRY,

    /** It can never be opened, and is refused. */
    UNREADABLE,
}

/** How far [ContactRequestRepository] got completing an acceptance the server took. */
private enum class Completion {
    COMPLETED,

    /** Pinning the sender failed in a way that trying again can fix, see [mls.isRetryable]. */
    RETRY,

    /** The sender can never be pinned, and the request was dropped. */
    DROPPED,
}
