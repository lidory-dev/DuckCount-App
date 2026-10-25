package group

import app.lidory.duckcount.GroupLimits
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSyncError
import database.data.local.dao.ContactDao
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.repository.ContactRepository
import database.domain.Currency
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.createGroupOf
import mls.deleteGroupQuietly
import mls.isRetryable
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newGroupId
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64

/**
 * How many invitees' key packages are asked for at once: enough that a hundred members do not
 * take a hundred round trips, few enough not to open a hundred connections.
 */
private const val KEY_PACKAGE_FETCHES_AT_ONCE = 8

/**
 * Creates a group of the user's contacts, and sends it to the server, which hands its one Welcome
 * to every invitee: they join it at their next sync, and read its fields with the key the Welcome
 * carries.
 *
 * In this order:
 * 1. The invitees are checked: at most [GroupLimits.MAX_MEMBERS] members with the user, once the
 *    session is read and before any key package is asked for, and then, under [withGroupsLock]
 *    like everything after, every one of them a contact this device pinned an identity for, none
 *    of them blocked, see [refusalOf].
 * 2. A key package is asked for each of them, a few at a time, see [fetchKeyPackages]. Any of them
 *    without one stops everything here, every one of them named, with nothing saved.
 * 3. The group is built with the library, its key travelling in the Welcome, see [build], and saved
 *    with its members and the request that will create it, in one transaction, before anything is
 *    sent. Its UUID is derived from the user's own and a nonce this device draws, as the server
 *    derives it, see `group_uuid` in lib-duckcount, so nobody else's request can create a group
 *    under it, and the group is the creator's from then on, whatever the server answers.
 * 4. It is sent, see [GroupCreationSender.send], its resend booked first, see
 *    [GroupCreationSender.bookResend]. Only a refusal undoes it; the server not answering, or
 *    answering that it cannot take it yet, leaves it to go out again, and the user is told it is
 *    created: it will reach its members once sent.
 * 5. Its key is backed up to the vault, see [GroupKeyBackUp]. One that cannot be now is by the next
 *    sync.
 *
 * Everything that can fail without leaving anything behind comes before the group is built. From
 * there to its being saved is not cancellable: a group built and never saved would be MLS state
 * nothing names, and a save cut short could not tell whether to delete it again. Once saved, the
 * caller may go: whatever was not sent, the next sync sends.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [restoreContacts] is [ContactRepository.restore],
 * [contacts] and [blocked] are [ContactDao.getUserUuids] and [IdentityConflictDao.getBlocked],
 * [client] is [MlsIdentityRepository.client], [consumeKeyPackage] is
 * [KeyPackageApi.consumeKeyPackage], [save] is [GroupDao.insertCreated], [bookResend] and [send]
 * are [GroupCreationSender.bookResend] and [GroupCreationSender.send], and [backUp] is
 * [GroupKeyBackUp.backUp], or what a test stands in for any of them.
 */
class GroupCreator internal constructor(
    private val userUuid: () -> String?,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val contacts: suspend () -> List<String>,
    private val blocked: suspend () -> List<String>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val consumeKeyPackage: suspend (userUuid: String) -> Result<ConsumeKeyPackageDto, NetworkError>,
    private val save: suspend (
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        pendingCreation: PendingGroupCreationEntity,
    ) -> Unit,
    private val bookResend: suspend () -> Unit,
    private val send: suspend (pendingCreation: PendingGroupCreationEntity) -> GroupCreationAnswer,
    private val backUp: suspend (groupUuid: String, groupKey: ByteArray) -> Unit,
) {
    constructor(
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        keyPackageApi: KeyPackageApi,
        groupDao: GroupDao,
        groupCreationSender: GroupCreationSender,
        groupKeyBackUp: GroupKeyBackUp,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        restoreContacts = contactRepository::restore,
        contacts = contactDao::getUserUuids,
        blocked = identityConflictDao::getBlocked,
        client = mlsIdentityRepository::client,
        consumeKeyPackage = keyPackageApi::consumeKeyPackage,
        save = groupDao::insertCreated,
        bookResend = groupCreationSender::bookResend,
        send = groupCreationSender::send,
        backUp = { groupUuid, groupKey -> groupKeyBackUp.backUp(groupUuid = groupUuid, groupKey = groupKey) },
    )

    /**
     * Creates a group of the user and [inviteeUuids], at least one of them and fewer than
     * [GroupLimits.MAX_MEMBERS], entitled [title], with [description] and [currency], and returns
     * its UUID, see [GroupCreator].
     */
    suspend fun create(
        title: String,
        description: String,
        currency: Currency,
        inviteeUuids: Set<String>,
    ): Result<String, GroupCreationError> {
        require(inviteeUuids.isNotEmpty()) { "A group is created with at least one invitee." }

        val selfUuid = userUuid() ?: return Result.Error(GroupCreationError.NoSession)

        // After the session: with none, the caller cannot tell the user from the invitees, and the
        // creation is refused as NoSession whatever their count. Before anything spends a key package.
        require(inviteeUuids.size < GroupLimits.MAX_MEMBERS) {
            "A group holds at most ${GroupLimits.MAX_MEMBERS} members, the creator included."
        }

        return withGroupsLock {
            createLocked(
                selfUuid = selfUuid,
                fields = GroupFields(title = title, description = description, currency = currency),
                inviteeUuids = inviteeUuids,
            )
        }
    }

    private suspend fun createLocked(
        selfUuid: String,
        fields: GroupFields,
        inviteeUuids: Set<String>,
    ): Result<String, GroupCreationError> {
        // Before the pins are read: until the contacts are back, a contact looks like a stranger,
        // and their pin is not there. As for a contact request.
        val restored = restoreContacts()

        if (restored is Result.Error) return Result.Error(GroupCreationError.Contacts(restored.error))

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupCreationError.Identity(opened.error))
        }

        refusalOf(client = client, inviteeUuids = inviteeUuids)?.let { return Result.Error(it) }

        // Known before the post, so that the fields can be sealed to it and the MLS group named by it.
        val groupId = newGroupId(creatorUuid = selfUuid)
            ?: return Result.Error(GroupCreationError.Crypto)
        val groupUuid = groupId.uuid
        val groupKey = generateSymmetricEncryptionKey()

        val sealedFields = fields.seal(groupKey = groupKey, groupUuid = groupUuid)
            ?: return Result.Error(GroupCreationError.Crypto)

        val keyPackages = when (val fetched = fetchKeyPackages(inviteeUuids = inviteeUuids)) {
            is Result.Success -> fetched.data
            is Result.Error -> return fetched
        }

        val pendingCreation = when (
            val built = withContext(NonCancellable) {
                build(
                    client = client,
                    groupUuid = groupUuid,
                    nonce = groupId.nonce,
                    selfUuid = selfUuid,
                    fields = fields,
                    groupKey = groupKey,
                    sealedFields = sealedFields,
                    keyPackages = keyPackages,
                ).also { saved ->
                    // Before the send, which the caller may cut short or the process not live
                    // through: booked here, nothing keeps the creation from going out again.
                    if (saved is Result.Success) bookResend()
                }
            }
        ) {
            is Result.Success -> built.data
            is Result.Error -> return built
        }

        when (val answer = send(pendingCreation)) {
            is GroupCreationAnswer.DeletedInvitees ->
                return Result.Error(GroupCreationError.Deleted(userUuids = answer.userUuids.toSet()))

            is GroupCreationAnswer.Refused -> return Result.Error(GroupCreationError.Network(answer.error))

            GroupCreationAnswer.Created,
            GroupCreationAnswer.AlreadyExists,
            is GroupCreationAnswer.RateLimited,
            is GroupCreationAnswer.Failed,
            -> Unit
        }

        backUp(groupUuid, groupKey)

        return Result.Success(groupUuid)
    }

    /**
     * Why [inviteeUuids] cannot all be invited, or null when they can: each of them has to be a
     * contact, unblocked, with an identity this device pinned, as for a channel to them, see
     * [channel.DirectChannelRepository.ensureChannel].
     */
    private suspend fun refusalOf(client: MlsClient, inviteeUuids: Set<String>): GroupCreationError? {
        val notContacts = inviteeUuids - contacts().toSet()

        if (notContacts.isNotEmpty()) return GroupCreationError.NotContacts(userUuids = notContacts)

        // A key package matching the identity this device pinned for them would go through,
        // when it is the one the vault disagrees with. See IdentityConflictEntity.
        val blocked = inviteeUuids.intersect(blocked().toSet())

        if (blocked.isNotEmpty()) return GroupCreationError.IdentityChanged(userUuids = blocked)

        val notPinned = try {
            inviteeUuids.filterTo(HashSet()) { client.pinnedSignatureKeyFor(peerUuid = it) == null }
        } catch (_: MlsException) {
            return GroupCreationError.Storage
        }

        return if (notPinned.isEmpty()) null else GroupCreationError.NotContacts(userUuids = notPinned)
    }

    /**
     * One key package for each of [inviteeUuids], a few at a time, or why not all of them could be
     * had: everybody who has none, see [GroupCreationError.Unreachable], unless the server could
     * not be asked for some, which may go through next time.
     *
     * Each one handed out is spent, so those of a creation that fails go to waste. A user publishes
     * a pool of them, and the last-resort one is never spent.
     */
    private suspend fun fetchKeyPackages(
        inviteeUuids: Set<String>,
    ): Result<List<InviteeKeyPackage>, GroupCreationError> {
        val permits = Semaphore(permits = KEY_PACKAGE_FETCHES_AT_ONCE)

        val fetched = coroutineScope {
            inviteeUuids.map { userUuid ->
                async { userUuid to permits.withPermit { consumeKeyPackage(userUuid) } }
            }.awaitAll()
        }

        val keyPackages = mutableListOf<InviteeKeyPackage>()
        val unreachable = mutableSetOf<String>()

        fetched.forEach { (userUuid, consumed) ->
            when (consumed) {
                is Result.Success -> {
                    // The server stores key packages without reading them, so this is whatever the
                    // invitee's client published.
                    val keyPackage = runCatching { Base64.Default.decode(consumed.data.keyPackage) }.getOrNull()

                    if (keyPackage == null) {
                        unreachable += userUuid
                    } else {
                        keyPackages += InviteeKeyPackage(userUuid = userUuid, keyPackage = keyPackage)
                    }
                }

                is Result.Error -> when (consumed.error) {
                    // They published none, or their account is gone, as for a channel.
                    NetworkError.NOT_FOUND -> unreachable += userUuid

                    else -> return Result.Error(GroupCreationError.Network(consumed.error))
                }
            }
        }

        if (unreachable.isNotEmpty()) return Result.Error(GroupCreationError.Unreachable(userUuids = unreachable))

        return Result.Success(keyPackages)
    }

    /**
     * Builds the MLS group of the user, [selfUuid], and the owners of [keyPackages], under the id
     * the library derives from [groupUuid], handing them [groupKey] in the Welcome, bare: the id
     * already binds the Welcome to [groupUuid], see [GroupSync]. Then saves it as that group,
     * see [GroupDao.insertCreated], with the request that creates it on the server, which it
     * returns: sending [nonce], from which the server derives [groupUuid] as this device did.
     *
     * Those of [keyPackages] the library cannot use, an expired one say, are named as having none,
     * as [fetchKeyPackages] names those who have none at all.
     *
     * A group that could not be saved is deleted again: nothing would ever send its Welcome.
     */
    private suspend fun build(
        client: MlsClient,
        groupUuid: String,
        nonce: ByteArray,
        selfUuid: String,
        fields: GroupFields,
        groupKey: ByteArray,
        sealedFields: SealedGroupFields,
        keyPackages: List<InviteeKeyPackage>,
    ): Result<PendingGroupCreationEntity, GroupCreationError> {
        val creation = try {
            client.createGroupOf(
                groupUuid = groupUuid,
                invitees = keyPackages,
                welcomePayload = groupKey,
            )
        } catch (exception: MlsException) {
            return Result.Error(
                when {
                    exception is MlsException.IdentityKeyMismatch ->
                        GroupCreationError.IdentityChanged(userUuids = setOf(exception.v1))

                    exception is MlsException.UnusableKeyPackages ->
                        GroupCreationError.Unreachable(userUuids = exception.v1.toSet())

                    exception.isRetryable() -> GroupCreationError.Storage
                    else -> GroupCreationError.Crypto
                },
            )
        }

        val pendingCreation = pendingCreationOf(
            groupUuid = groupUuid,
            body = CreateGroupBody(
                nonce = Base64.Default.encode(nonce),
                title = sealedFields.title,
                description = sealedFields.description,
                currency = sealedFields.currency,
                welcomeMessage = Base64.Default.encode(creation.welcome),
                invitedMemberUuids = keyPackages.map { it.userUuid },
            ),
        )

        val group = GroupEntity(
            uuid = groupUuid,
            title = fields.title,
            description = fields.description,
            currency = fields.currency,
            ownerUuid = selfUuid,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            isBackedUp = false,
        )

        val members = listOf(GroupMemberEntity(userUuid = selfUuid, groupUuid = groupUuid, isAdmin = true)) +
            keyPackages.map { GroupMemberEntity(userUuid = it.userUuid, groupUuid = groupUuid, isAdmin = false) }

        try {
            save(group, members, pendingCreation)
        } catch (_: Exception) {
            client.deleteGroupQuietly(mlsGroupId = creation.mlsGroupId)

            return Result.Error(GroupCreationError.Storage)
        }

        return Result.Success(pendingCreation)
    }
}
