package contact

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.remote.ContactApi
import database.data.remote.SendContactRequestBody
import database.data.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.pinMemberOf
import push.WakeList
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultError
import vault.VaultRepository
import kotlin.io.encoding.Base64

/**
 * Sends the user's contact requests: to the holder of a discovery username, whom the lookup
 * resolves along with one of their key packages, see
 * [ContactRequestRepository.sendContactRequest][database.data.repository.ContactRequestRepository.sendContactRequest],
 * and to a member of one of the user's groups, addressed by their UUID, see [sendToMember].
 * Either way, from the key package on, the request is built and sent the same, see [sendTo].
 *
 * A request to a group member goes in this order, the cheapest refusal first, and nothing spent
 * before it:
 * 1. The contacts are brought back from the vault, as for a username: until they are, one of
 *    them looks like a stranger, see [ContactRepository.restore].
 * 2. The member is checked: not the user, not a contact already, no request of the user's out to
 *    them already that the server is known to hold, see [OutgoingContactRequestEntity.isStored],
 *    and not blocked, see [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity].
 *    All of it before a key package of theirs is spent, which the server only refuses a request
 *    after.
 * 3. The group is checked: one of the user's, which they were not removed from, with its MLS state
 *    on this device, and whose creator is not blocked, the group's tree being their word. The
 *    member is then pinned to the identity they hold in it, see [pinMemberOf].
 * 4. One of their key packages is asked for, and the request built from it and sent, see
 *    [sendTo], refused if the key package carries any identity but the one just pinned.
 *
 * [selfUuid] is [getAuthenticatedUserUuid], [restoreContacts] and [isContact] are
 * [ContactRepository.restore] and [ContactRepository.isContact], [hasStoredOutgoingTo] is
 * [ContactRequestDao.hasStoredOutgoingTo], [isBlocked] is [IdentityConflictDao.isBlocked], [group]
 * is [GroupDao.get], [client] is [MlsIdentityRepository.client], [consumeKeyPackage] is
 * [KeyPackageApi.consumeKeyPackage], [profileKey] is
 * [VaultRepository.getOrCreateProfileEncryptionKey], [insertOutgoing], [markStored],
 * [markStoredTo] and [deleteOutgoing] are [ContactRequestDao.insertOutgoing],
 * [ContactRequestDao.markStored], [ContactRequestDao.markStoredTo] and
 * [ContactRequestDao.deleteOutgoing], [rebuildWakeList] is [WakeList.rebuild], and [send] is
 * [ContactApi.sendContactRequest], or what a test stands in for any of them.
 */
class ContactRequestSender internal constructor(
    private val selfUuid: () -> String?,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val isContact: suspend (userUuid: String) -> Boolean,
    private val hasStoredOutgoingTo: suspend (recipientUserUuid: String) -> Boolean,
    private val isBlocked: suspend (peerUuid: String) -> Boolean,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val consumeKeyPackage: suspend (userUuid: String) -> Result<ConsumeKeyPackageDto, NetworkError>,
    private val profileKey: suspend () -> Result<ByteArray, VaultError>,
    private val insertOutgoing: suspend (request: OutgoingContactRequestEntity) -> Unit,
    private val markStored: suspend (recipientUserUuid: String, mlsGroupId: ByteArray) -> Unit,
    private val markStoredTo: suspend (recipientUserUuid: String) -> Unit,
    private val deleteOutgoing: suspend (recipientUserUuid: String, mlsGroupId: ByteArray) -> Unit,
    private val rebuildWakeList: suspend () -> Unit,
    private val send: suspend (body: SendContactRequestBody) -> EmptyResult<NetworkError>,
) {
    constructor(
        contactRepository: ContactRepository,
        contactRequestDao: ContactRequestDao,
        identityConflictDao: IdentityConflictDao,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        keyPackageApi: KeyPackageApi,
        vaultRepository: VaultRepository,
        contactApi: ContactApi,
        wakeList: WakeList,
    ) : this(
        selfUuid = ::getAuthenticatedUserUuid,
        restoreContacts = contactRepository::restore,
        isContact = contactRepository::isContact,
        hasStoredOutgoingTo = contactRequestDao::hasStoredOutgoingTo,
        isBlocked = identityConflictDao::isBlocked,
        group = groupDao::get,
        client = mlsIdentityRepository::client,
        consumeKeyPackage = keyPackageApi::consumeKeyPackage,
        profileKey = vaultRepository::getOrCreateProfileEncryptionKey,
        insertOutgoing = contactRequestDao::insertOutgoing,
        markStored = contactRequestDao::markStored,
        markStoredTo = contactRequestDao::markStoredTo,
        deleteOutgoing = contactRequestDao::deleteOutgoing,
        rebuildWakeList = wakeList::rebuild,
        send = contactApi::sendContactRequest,
    )

    /**
     * Asks [userUuid], a member of the group [groupUuid], to become a contact, see
     * [ContactRequestSender] for the order it goes in, and
     * [ContactRequestRepository.sendContactRequest][database.data.repository.ContactRequestRepository.sendContactRequest]
     * for why.
     */
    internal suspend fun sendToMember(userUuid: String, groupUuid: String): EmptyResult<SendContactRequestError> {
        // As for a username, and before anything else: the checks below read the contacts and
        // their pins.
        val restored = restoreContacts()

        if (restored is Result.Error) return Result.Error(SendContactRequestError.Contacts(restored.error))

        if (userUuid == selfUuid()) return Result.Error(SendContactRequestError.Self)

        if (isContact(userUuid)) return Result.Error(SendContactRequestError.AlreadyContact)

        // The server refuses it too, with a `409`, but only once one of their key packages was
        // spent on it. One sent in doubt is left to that refusal: it may never have reached it.
        if (hasStoredOutgoingTo(userUuid)) return Result.Error(SendContactRequestError.AlreadyPending)

        // [sendTo] refuses them too, but only once one of their key packages is spent.
        if (isBlocked(userUuid)) return Result.Error(SendContactRequestError.IdentityChanged)

        // A group the user is no longer in, removed or left, has had its MLS state deleted, and
        // one brought back after a log-out never had any here: nothing is left to vouch for
        // anybody in it.
        val held = group(groupUuid)?.takeUnless { it.isLeft } ?: return Result.Error(SendContactRequestError.NotInGroup)
        val mlsGroupId = held.mlsGroupId ?: return Result.Error(SendContactRequestError.NotInGroup)

        // Its tree is its creator's word, taken on the identity this device pinned for them,
        // which the vault disputes once they are blocked: that pin may be an impostor's, and so
        // may the identity anybody they added holds in it. Their Commits are applied all the
        // same, so the group cannot tell.
        if (isBlocked(held.ownerUuid)) return Result.Error(SendContactRequestError.NotInGroup)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(SendContactRequestError.Identity(opened.error))
        }

        // Before the key package is asked for, so that the request's group is built against the
        // identity the group vouches for, never the one the server serves, see [sendTo].
        try {
            client.pinMemberOf(mlsGroupId = mlsGroupId, userUuid = userUuid)
        } catch (exception: MlsException) {
            return Result.Error(
                when (exception) {
                    // Its state gone from under the read above, the user removed from it
                    // meanwhile, or a tree without them: added by a Commit this device has not
                    // read yet, or removed by one it read since the caller looked.
                    is MlsException.GroupNotFound,
                    is MlsException.UnexpectedMembers,
                    -> SendContactRequestError.NotInGroup

                    is MlsException.IdentityKeyMismatch -> SendContactRequestError.IdentityChanged
                    else -> SendContactRequestError.Crypto
                },
            )
        }

        val keyPackage = when (val fetched = consumeKeyPackage(userUuid)) {
            is Result.Success -> fetched.data.keyPackage
            is Result.Error -> return Result.Error(fetched.error.toSendContactRequestError())
        }

        return sendTo(recipientUserUuid = userUuid, keyPackage = keyPackage)
    }

    /**
     * Sends [recipientUserUuid] a request built from [keyPackage], one of theirs the server
     * served, Base64 encoded: the MLS group the two of them will share is created with them in it,
     * and the user's profile encryption key travels encrypted to that group next to the Welcome
     * that lets them join it. Creating it checks the key package against the identity pinned for
     * them, if any, and pins it otherwise.
     *
     * The request is recorded with its group before it goes out, see
     * [OutgoingContactRequestEntity], which lets its recipient wake the user with their
     * acceptance, see [WakeList], and marked stored once the server took it, see
     * [OutgoingContactRequestEntity.isStored]. A request the server provably did not store
     * deletes both again, and one refused as already pending marks the others to them stored
     * instead. Any other failure keeps the group, and its row once written, see
     * [ContactRequestRepository.sendContactRequest][database.data.repository.ContactRequestRepository.sendContactRequest].
     */
    internal suspend fun sendTo(recipientUserUuid: String, keyPackage: String): EmptyResult<SendContactRequestError> {
        // The server stores key packages without reading them, so this is whatever the
        // recipient's client published.
        val decodedKeyPackage = runCatching { Base64.Default.decode(keyPackage) }.getOrNull()
            ?: return Result.Error(SendContactRequestError.Crypto)

        // Everything that can fail without leaving anything behind comes first. Once the group
        // exists, a failure the library reports deletes it again, and the caller going away
        // leaves it behind, harmlessly: its Welcome never left the device.
        val profileEncryptionKey = when (val read = profileKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(SendContactRequestError.Vault(read.error))
        }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(SendContactRequestError.Identity(opened.error))
        }

        // A key package matching the identity this device pinned for them would go through,
        // when it is the one the vault disagrees with. See IdentityConflictEntity.
        if (isBlocked(recipientUserUuid)) {
            return Result.Error(SendContactRequestError.IdentityChanged)
        }

        val creation = try {
            withContext(Dispatchers.IO) {
                client.createGroup(
                    groupUuid = null,
                    invitees = listOf(
                        InviteeKeyPackage(userUuid = recipientUserUuid, keyPackage = decodedKeyPackage),
                    ),
                    welcomePayload = null,
                )
            }
        } catch (_: MlsException.IdentityKeyMismatch) {
            return Result.Error(SendContactRequestError.IdentityChanged)
        } catch (_: MlsException) {
            return Result.Error(SendContactRequestError.Crypto)
        }

        val envelope = try {
            val message = client.encryptApplicationMessage(
                mlsGroupId = creation.mlsGroupId,
                payload = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize(),
            )

            ContactRequestEnvelope(welcome = creation.welcome, message = message)
        } catch (_: MlsException) {
            client.deleteGroupQuietly(mlsGroupId = creation.mlsGroupId)

            return Result.Error(SendContactRequestError.Crypto)
        }

        // Before the request goes out, so that no answer can arrive with nothing to match it.
        val outgoing = OutgoingContactRequestEntity(
            mlsGroupId = creation.mlsGroupId,
            recipientUserUuid = recipientUserUuid,
            handedProfileKeyHash = profileEncryptionKeyPartialHash(profileEncryptionKey),
        )

        insertOutgoing(outgoing)

        rebuildWakeList()

        val sent = send(
            SendContactRequestBody(
                recipientUserUuid = recipientUserUuid,
                ciphertext = envelope.serialize(),
            ),
        )

        if (sent is Result.Error) {
            if (sent.error in REQUEST_NEVER_STORED) {
                deleteOutgoing(outgoing.recipientUserUuid, outgoing.mlsGroupId)
                rebuildWakeList()
                client.deleteGroupQuietly(mlsGroupId = creation.mlsGroupId)
            }

            // One of the user's is pending all the same: one recorded here whose sending ended
            // in doubt, unless a log-out took its row.
            if (sent.error == NetworkError.CONFLICT) markStoredTo(recipientUserUuid)

            return Result.Error(sent.error.toSendContactRequestError())
        }

        // Only now, so that a request sent in doubt never reads as one out to them.
        markStored(outgoing.recipientUserUuid, outgoing.mlsGroupId)

        return Result.Success(Unit)
    }
}

/**
 * The failures that prove a contact request was not stored: the host never resolved, so
 * nothing was sent; the token was turned away before the handler ran; the sender is over
 * their quota, which is checked before anything is written; or the handler wrote nothing,
 * the recipient being unknown (`404`, which the proxy in front of it also answers while the
 * API is being redeployed) or asked already (`409`, about an earlier request, which stays,
 * marked stored if this device recorded it). Every other failure (a timeout, a dropped
 * connection, a `5xx` the proxy may return after the commit) leaves open that it was.
 */
private val REQUEST_NEVER_STORED = setOf(
    NetworkError.NO_INTERNET,
    NetworkError.UNAUTHORIZED,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
)

/**
 * Maps the refusals of the lookup, of the key package fetch and of the request itself, the rest
 * being plain network errors.
 */
internal fun NetworkError.toSendContactRequestError(): SendContactRequestError = when (this) {
    NetworkError.NOT_FOUND -> SendContactRequestError.NotFound
    NetworkError.CONFLICT -> SendContactRequestError.AlreadyPending
    NetworkError.TOO_MANY_REQUESTS -> SendContactRequestError.RateLimited
    else -> SendContactRequestError.Network(this)
}
