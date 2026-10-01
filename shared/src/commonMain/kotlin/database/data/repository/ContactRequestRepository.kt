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
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.mappers.toContactRequest
import database.data.mappers.toContactRequestEntity
import database.data.remote.AcceptContactRequestBody
import database.data.remote.ContactApi
import database.data.remote.SendContactRequestBody
import database.domain.ContactRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import profile.ProfileRepository
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
import vault.VaultRepository
import kotlin.io.encoding.Base64

/**
 * The user's contact requests: the ones addressed to them, and the ones they send.
 *
 * For the incoming ones the server is the only source, and the local table follows it.
 * What the table adds is what opening a request yields: the group it created, and the key
 * to its sender's profile. Opening one joins that group, and a group is only ever joined
 * once, so that can only ever happen once.
 */
class ContactRequestRepository(
    private val contactRequestDao: ContactRequestDao,
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
     * Every step is attempted even when one fails, and the first failure is reported:
     * whatever did not get done is picked up by the next refresh.
     */
    suspend fun refresh(): EmptyResult<ContactSyncError> = mutex.withLock { refreshLocked() }

    /**
     * Accepts a request: pins the MLS identity its sender holds in the request's group,
     * hands them the user's own profile encryption key, and makes them a contact.
     *
     * Pinned only now, never when the request is opened: opening happens to every request
     * the server lists, from anyone, and one the user refuses or never answers must not
     * decide who its sender is.
     *
     * The key travels encrypted to the group the request created, and the server delivers
     * it to the sender as a direct message. The sender becomes a contact on this device once
     * the server has taken the answer, and reaches the vault with the next
     * [ContactRepository.backUp].
     */
    suspend fun accept(requestUuid: String): EmptyResult<AnswerContactRequestError> =
        mutex.withLock { acceptLocked(requestUuid = requestUuid) }

    /**
     * Refuses a request, deleting it along with the group it created. Its sender is not
     * told, and may send another one.
     */
    suspend fun refuse(requestUuid: String): EmptyResult<AnswerContactRequestError> =
        mutex.withLock {
            when (val refusal = refuseLocked(requestUuid = requestUuid)) {
                is Result.Success -> refusal
                is Result.Error -> Result.Error(AnswerContactRequestError.Network(refusal.error))
            }
        }

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
     * group, makes them a contact. A request the server turns down deletes both again.
     */
    suspend fun sendContactRequest(username: String): EmptyResult<SendContactRequestError> {
        val digest = hashDiscoveryUsername(username = username)
            ?: return Result.Error(SendContactRequestError.InvalidUsername)

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

        // Everything that can fail without leaving anything behind comes first: once the
        // group exists, every failure has to delete it.
        val profileEncryptionKey = when (val read = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(SendContactRequestError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(SendContactRequestError.Identity(opened.error))
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
            contactRequestDao.deleteOutgoing(
                recipientUserUuid = outgoing.recipientUserUuid,
                mlsGroupId = outgoing.mlsGroupId,
            )
            client.deleteGroupQuietly(mlsGroupId = group.mlsGroupId)

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

    private suspend fun refreshLocked(): EmptyResult<ContactSyncError> {
        val listed = when (val response = contactApi.getContactRequests()) {
            is Result.Success -> response.data.requests.map { it.toContactRequestEntity() }
            is Result.Error -> return Result.Error(ContactSyncError.Network(response.error))
        }

        val listedUuids = listed.mapTo(HashSet()) { it.uuid }
        val vanished = contactRequestDao.getAll().filter { it.uuid !in listedUuids }

        contactRequestDao.reconcile(vanishedUuids = vanished.map { it.uuid }, listed = listed)

        // A request vanishes when its sender deletes their account, or accepts the user's
        // own request to them, which clears this one. Either way its group is of no use.
        val abandonedGroupIds = vanished.mapNotNull { it.mlsGroupId }
        val unopened = contactRequestDao.getUnopened()

        var failure: ContactSyncError? = null

        // Opening the MLS client is not free, and most refreshes leave it nothing to do.
        if (abandonedGroupIds.isNotEmpty() || unopened.isNotEmpty()) {
            when (val opened = mlsIdentityRepository.client()) {
                is Result.Error -> failure = ContactSyncError.Identity(opened.error)

                is Result.Success -> {
                    abandonedGroupIds.forEach { opened.data.deleteGroupQuietly(mlsGroupId = it) }

                    for (request in unopened) {
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

        // The join refuses any group but one holding exactly the user and the sender the
        // server authenticated, so every message read in it, the card included, is theirs.
        // It pins nobody: that waits for the user to accept the request.
        val mlsGroupId = try {
            client.joinGroupFrom(
                welcome = envelope.welcome,
                peerUuid = request.senderUserUuid,
                pinPeer = false,
            )
        } catch (exception: MlsException) {
            if (exception.isRetryable()) return Result.Error(ContactSyncError.Storage)

            return refuseUnreadable(requestUuid = request.uuid)
        }

        // A group is only ever joined once, so from here on a request that cannot be read
        // now never will be, whatever the reason.
        val reading = client.readApplicationMessage(mlsGroupId = mlsGroupId, message = envelope.message)

        val card = (reading as? ApplicationMessageReading.Read)
            ?.let { ContactCard.parse(plaintext = it.payload) }

        if (reading !is ApplicationMessageReading.Read || card == null) {
            client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

            return refuseUnreadable(requestUuid = request.uuid)
        }

        contactRequestDao.markOpened(
            uuid = request.uuid,
            mlsGroupId = mlsGroupId,
            profileEncryptionKey = card.profileEncryptionKey,
        )

        return Result.Success(Unit)
    }

    /** Reads the name of every sender this device has none for yet. */
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

                is Result.Error -> failure = failure ?: ContactSyncError.Profile(read.error)
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    private suspend fun acceptLocked(requestUuid: String): EmptyResult<AnswerContactRequestError> {
        // Only opened requests are shown, but a refresh can drop one from under the screen.
        val request = contactRequestDao.getOpened(uuid = requestUuid)
            ?: return Result.Error(AnswerContactRequestError.NotFound)

        val profileEncryptionKey = when (val read = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(AnswerContactRequestError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(AnswerContactRequestError.Identity(opened.error))
        }

        val message = try {
            client.pinPeerIn(mlsGroupId = request.mlsGroupId, peerUuid = request.senderUserUuid)

            client.encryptApplicationMessage(
                mlsGroupId = request.mlsGroupId,
                payload = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize(),
            )
        } catch (_: MlsException.IdentityKeyMismatch) {
            return Result.Error(AnswerContactRequestError.IdentityChanged)
        } catch (_: MlsException) {
            return Result.Error(AnswerContactRequestError.Crypto)
        }

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
            if (acceptance.error != NetworkError.NOT_FOUND) {
                return Result.Error(AnswerContactRequestError.Network(acceptance.error))
            }

            forget(requestUuid = request.uuid)

            return Result.Error(AnswerContactRequestError.NotFound)
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

        return Result.Success(Unit)
    }

    private suspend fun refuseLocked(requestUuid: String): EmptyResult<NetworkError> {
        val refusal = contactApi.refuseContactRequest(requestUuid = requestUuid)

        // Not found means the request is gone already (its sender deleted their account, or
        // accepted the user's own request to them), which is all refusing it was for.
        if (refusal is Result.Error && refusal.error != NetworkError.NOT_FOUND) return refusal

        forget(requestUuid = requestUuid)

        return Result.Success(Unit)
    }

    /** Refuses a request that can never be opened, see [refresh]. */
    private suspend fun refuseUnreadable(requestUuid: String): EmptyResult<ContactSyncError> =
        when (val refusal = refuseLocked(requestUuid = requestUuid)) {
            is Result.Success -> refusal
            is Result.Error -> Result.Error(ContactSyncError.Network(refusal.error))
        }

    /** Drops a request the server no longer holds, along with its group if it was opened. */
    private suspend fun forget(requestUuid: String) {
        val mlsGroupId = contactRequestDao.get(uuid = requestUuid)?.mlsGroupId

        contactRequestDao.delete(uuids = listOf(requestUuid))

        if (mlsGroupId == null) return

        when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data.deleteGroupQuietly(mlsGroupId = mlsGroupId)

            // The group stays behind. Nothing its sender sends in it is acted on until this
            // device pins them, see DirectMessageSync.
            is Result.Error -> Unit
        }
    }
}

/** Maps the lookup's and the request's own refusals, the rest being plain network errors. */
private fun NetworkError.toSendContactRequestError(): SendContactRequestError = when (this) {
    NetworkError.NOT_FOUND -> SendContactRequestError.NotFound
    NetworkError.CONFLICT -> SendContactRequestError.AlreadyPending
    NetworkError.TOO_MANY_REQUESTS -> SendContactRequestError.RateLimited
    else -> SendContactRequestError.Network(this)
}
