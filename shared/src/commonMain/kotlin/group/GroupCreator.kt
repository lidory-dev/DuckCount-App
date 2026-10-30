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
import database.data.remote.EncryptedFieldDto
import database.data.repository.ContactRepository
import database.domain.Currency
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.createGroupOf
import mls.deleteGroupQuietly
import mls.isRetryable
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newGroupId
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.ProfileKeyVault
import vault.VaultError
import kotlin.io.encoding.Base64

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
 * 2. The group's key is drawn, its fields, the title and description taken without the whitespace
 *    around them, signed by the user as its first version, see [FIRST_GROUP_REVISION], and sealed
 *    under it, and the creator's copy of it sealed for the server to keep, see
 *    [GroupKeyCopies.seal], which reads the key ring: a key ring not answering stops everything
 *    here, with no key package spent. Then a key package is asked for each of them, a few at a
 *    time, see [fetchKeyPackages]. Any of them without one stops everything here, every one of
 *    them named, with nothing saved.
 * 3. The group is built with the library, its key travelling in the Welcome with the key to every
 *    member's profile this device holds, see [build] and [profileKeysToHandOut], and saved with its
 *    members and the request that will create it, the creator's copy of its key in it, see
 *    [CreateGroupBody.keyCopy], in one transaction, before anything is sent. Its UUID is derived
 *    from the user's own and a nonce this device draws, as the server derives it, see
 *    `group_uuid` in lib-duckcount, so nobody else's request can create a group under it, and the
 *    group is the creator's from then on, whatever the server answers.
 * 4. It is sent, see [GroupCreationSender.send], its resend booked first, see
 *    [GroupCreationSender.bookResend]. Only a refusal undoes it; the server not answering, or
 *    answering that it cannot take it yet, leaves it to go out again, and the user is told it is
 *    created: it will reach its members once sent, and the server will keep the creator's copy of
 *    its key from then on.
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
 * are [GroupCreationSender.bookResend] and [GroupCreationSender.send], [sealKeyCopy] is
 * [GroupKeyCopies.seal], [ownProfileKey] is [ProfileKeyVault.getOrCreateProfileEncryptionKey],
 * null when it fails, [contactProfileKey] is the key [ContactDao.get] holds and
 * [learnedProfileKey] is [MemberProfileRepository.keyFor], or what a test stands in for any of
 * them.
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
    private val sealKeyCopy: suspend (
        groupUuid: String,
        groupKey: ByteArray,
        keyGeneration: Long,
    ) -> Result<EncryptedFieldDto, VaultError>,
    private val ownProfileKey: suspend () -> ByteArray?,
    private val contactProfileKey: suspend (userUuid: String) -> ByteArray?,
    private val learnedProfileKey: suspend (userUuid: String) -> ByteArray?,
) {
    constructor(
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        keyPackageApi: KeyPackageApi,
        groupDao: GroupDao,
        groupCreationSender: GroupCreationSender,
        groupKeyCopies: GroupKeyCopies,
        profileKeyVault: ProfileKeyVault,
        memberProfileRepository: MemberProfileRepository,
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
        sealKeyCopy = groupKeyCopies::seal,
        ownProfileKey = { (profileKeyVault.getOrCreateProfileEncryptionKey() as? Result.Success)?.data },
        contactProfileKey = { userUuid -> contactDao.get(userUuid = userUuid)?.profileEncryptionKey },
        learnedProfileKey = memberProfileRepository::keyFor,
    )

    /**
     * Creates a group of the user and [inviteeUuids], at least one of them and fewer than
     * [GroupLimits.MAX_MEMBERS], entitled [title], with [description] and [currency], and returns
     * its UUID, see [GroupCreator]. The title and description are taken without the whitespace
     * around them, as an edit takes them, see [GroupEditor].
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
                fields = GroupFields(title = title.trim(), description = description.trim(), currency = currency),
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

        // Signed once and sealed with the fields, as the key copy is, so that every resend of the
        // creation carries the same version, see CreateGroupBody.signature.
        val sealedVersion = fields.signedAs(client = client, groupUuid = groupUuid, revision = FIRST_GROUP_REVISION)
            ?.seal(groupKey = groupKey, groupUuid = groupUuid)
            ?: return Result.Error(GroupCreationError.Crypto)

        // The creator's copy of the key, which the creation carries, see CreateGroupBody.keyCopy:
        // sealed before any key package is asked for, which a key ring not answering would spend
        // for nothing.
        val keyCopy = when (val sealed = sealKeyCopy(groupUuid, groupKey, FIRST_KEY_GENERATION)) {
            is Result.Success -> sealed.data
            is Result.Error -> return Result.Error(GroupCreationError.Vault(sealed.error))
        }

        val keyPackages = when (
            val fetched = fetchKeyPackages(inviteeUuids = inviteeUuids, consumeKeyPackage = consumeKeyPackage)
        ) {
            is KeyPackageFetch.Fetched -> fetched.keyPackages

            is KeyPackageFetch.Unreachable ->
                return Result.Error(GroupCreationError.Unreachable(userUuids = fetched.userUuids))

            is KeyPackageFetch.Failed -> return Result.Error(GroupCreationError.Network(fetched.error))
        }

        // Every member's, for the others to read their names with: each invitee's, whom the user
        // seats as a contact, and the user's own, unless the vault does not answer, which leaves
        // the group owed it, posted by the next sync, see GroupProfileKeyDelivery.
        val profileKeys = profileKeysToHandOut(
            memberUuids = listOf(selfUuid) + keyPackages.map { it.userUuid },
            selfUuid = selfUuid,
            ownProfileKey = ownProfileKey(),
            contactProfileKey = contactProfileKey,
            learnedProfileKey = learnedProfileKey,
        )

        val pendingCreation = when (
            val built = withContext(NonCancellable) {
                build(
                    client = client,
                    groupUuid = groupUuid,
                    nonce = groupId.nonce,
                    selfUuid = selfUuid,
                    fields = fields,
                    groupKey = groupKey,
                    sealedVersion = sealedVersion,
                    keyCopy = keyCopy,
                    keyPackages = keyPackages,
                    profileKeys = profileKeys,
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

        return Result.Success(groupUuid)
    }

    /**
     * Why [inviteeUuids] cannot all be invited, or null when they can, see [inviteeRefusalOf]: each
     * of them has to be a contact, unblocked, with an identity this device pinned.
     */
    private suspend fun refusalOf(client: MlsClient, inviteeUuids: Set<String>): GroupCreationError? =
        when (
            val refusal = client.inviteeRefusalOf(inviteeUuids = inviteeUuids, contacts = contacts, blocked = blocked)
        ) {
            is InviteeRefusal.NotContacts -> GroupCreationError.NotContacts(userUuids = refusal.userUuids)
            is InviteeRefusal.IdentityChanged -> GroupCreationError.IdentityChanged(userUuids = refusal.userUuids)
            InviteeRefusal.Storage -> GroupCreationError.Storage
            null -> null
        }

    /**
     * Builds the MLS group of the user, [selfUuid], and the owners of [keyPackages], under the id
     * the library derives from [groupUuid], handing them [groupKey] in the Welcome as the first
     * generation of its keys, see [FIRST_KEY_GENERATION], with [profileKeys], the key to the
     * profile of each member this device holds, see [welcomePayloadOf]: the id already binds the
     * Welcome to [groupUuid], see [GroupSync]. Then
     * saves it as that group, see [GroupDao.insertCreated], holding the user's own key among
     * [profileKeys] for them, see [GroupEntity.handedProfileKeyHash], with the request that creates
     * it on the server, which it returns: sending [nonce], from which the server derives
     * [groupUuid] as this device did, [sealedVersion], [fields] as the group's first version, and
     * [keyCopy], the user's copy of [groupKey].
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
        sealedVersion: SealedGroupVersion,
        keyCopy: EncryptedFieldDto,
        keyPackages: List<InviteeKeyPackage>,
        profileKeys: Map<String, ByteArray>,
    ): Result<PendingGroupCreationEntity, GroupCreationError> {
        val creation = try {
            client.createGroupOf(
                groupUuid = groupUuid,
                invitees = keyPackages,
                welcomePayload = welcomePayloadOf(
                    groupKey = groupKey,
                    keyGeneration = FIRST_KEY_GENERATION,
                    profileKeys = profileKeys,
                ),
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
                title = sealedVersion.fields.title,
                description = sealedVersion.fields.description,
                currency = sealedVersion.fields.currency,
                signature = sealedVersion.signature,
                welcomeMessage = Base64.Default.encode(creation.welcome),
                invitedMemberUuids = keyPackages.map { it.userUuid },
                keyCopy = keyCopy,
            ),
        )

        val group = GroupEntity(
            uuid = groupUuid,
            title = fields.title,
            description = fields.description,
            currency = fields.currency,
            infoRevision = sealedVersion.revision,
            ownerUuid = selfUuid,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = false,
            isLeft = false,
            handedProfileKeyHash = profileKeys[selfUuid]?.let { profileEncryptionKeyPartialHash(it) },
            // Nothing written in it the user has yet to see: none of its members writes anything
            // before the server takes its creation, see GroupExpenseWriter, and whatever they write
            // after is news to the user.
            hasTakenInExpenses = true,
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
