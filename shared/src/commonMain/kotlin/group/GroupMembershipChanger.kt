package group

import app.lidory.duckcount.GroupLimits
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSyncError
import database.data.local.dao.ContactDao
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.repository.ContactRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.GroupRoster
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.addMembersTo
import mls.discardPendingCommitIn
import mls.encryptApplicationMessage
import mls.isRetryable
import mls.removeMembersFrom
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
import vault.ProfileKeyVault
import kotlin.io.encoding.Base64

/** What a change to a group's members came to, when it did not fail, see [GroupMembershipChanger]. */
enum class MembershipChangeOutcome {
    /**
     * The server took the change, or there was none to make: everybody named was a member
     * already, or for a removal no member any more. For a removal, the key rotation after it may
     * still be waiting on the server, see [GroupMembershipSender].
     */
    MADE,

    /**
     * The server has not been heard to take the change yet, see [GroupMembershipAnswer.Failed] and
     * [GroupMembershipAnswer.RateLimited]: it is sent again until it is, and reaches the members
     * then.
     */
    PENDING,
}

/**
 * Adds members to a group the user created, or removes some, and sends the change to the server,
 * which hands the Commit to the group's members and the Welcome to those it adds. Only the creator
 * changes a group's members: the members' devices take Commits from nobody else, see
 * [GroupEntity.ownerUuid].
 *
 * In this order:
 * 1. The group is checked, see [changeable]: the user created it, and so is still in it, a creator
 *    never being removed from their own group, and this device holds its MLS state, with no change
 *    to it waiting on the server, its creation included. The library builds one Commit at a time
 *    per group, and a Commit built over a change the server may hold would be refused by every
 *    member who got that change.
 * 2. For an addition, the new members are checked as a creation checks its invitees, see
 *    [inviteeRefusalOf], with at most [GroupLimits.MAX_MEMBERS] members once they are in, and a key
 *    package is asked for each of them, see [fetchKeyPackages]. Anybody named who is a member
 *    already, or for a removal who is no member, is left out: a change asked for twice is made
 *    once.
 * 3. The Commit is built with the library, an addition's Welcome handing the new members the
 *    group's key and the key to every member's profile this device holds, see
 *    [profileKeysToHandOut], and saved with the request that will make it, see
 *    [PendingMembershipChangeEntity], before anything is sent. The members already in the group
 *    are handed the same keys in an application message of the epoch before the Commit's, see
 *    [AddGroupMembersBody.profileKeysMessage], which goes with it. An addition handing over none
 *    of the user's own leaves the group owed it, see [GroupEntity.handedProfileKeyHash].
 * 4. It is sent, see [GroupMembershipSender.send], its resend booked first, see
 *    [GroupMembershipSender.bookResend]. Only a refusal undoes it; the server not answering, or
 *    answering that it cannot take it yet, leaves it to go out again, see
 *    [MembershipChangeOutcome.PENDING]. A removal taken is followed by the rotation of the group's
 *    key, which the sender sees to.
 *
 * The user removes members by hand, and the group sync removes those the server no longer lists:
 * members who left the group, on purpose, see [GroupLeaver], or as a device declining it does, and
 * accounts deleted since, see [GroupSync]. Both go through the same removal, and the same key
 * rotation after it.
 *
 * Everything that can fail without leaving anything behind comes before the Commit is built. From
 * there to the answer is not cancellable: a Commit built and never saved would hold the group's
 * next change back, and a change sent and left there would wait for the next sync to reach its
 * members, a removal its key rotation.
 *
 * A change can wait on a sync under way for as long as that sync takes, and the device can lock
 * meanwhile, which leaves nothing the vault holds readable: who is signed in, the user's MLS
 * identity until it is opened in this process, the contacts still to bring back, and the key to
 * the user's profile. Before the Commit is built, a change the user asked for is not made then,
 * see [GroupMembershipError.DeviceLocked], and an addition reads all of it before it asks for any
 * key package, which would be spent for nothing. The sync's own removals read the vault as is
 * instead, and its pass stops at the first read that throws, see [removeUnderGroupsLock]. After the
 * Commit is built, nothing reads the vault but the backup of a rotated key, which the next sync
 * makes when the vault would not take it, see [GroupMembershipSender].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [restoreContacts] is [ContactRepository.restore],
 * [contacts] and [blocked] are [ContactDao.getUserUuids] and [IdentityConflictDao.getBlocked],
 * [client] is [MlsIdentityRepository.client], [consumeKeyPackage] is
 * [KeyPackageApi.consumeKeyPackage], [group] is [GroupDao.get], [hasPendingChange] is
 * [GroupDao.hasPendingChange], [savePending] is [GroupDao.upsertMembershipChange], [bookResend]
 * and [send] are [GroupMembershipSender.bookResend] and [GroupMembershipSender.send],
 * [ownProfileKey] is [ProfileKeyVault.getOrCreateProfileEncryptionKey], null when it fails,
 * [contactProfileKey] is the key [ContactDao.get] holds, [learnedProfileKey] is
 * [MemberProfileRepository.keyFor] and [oweProfileKey] is [GroupDao.setHandedProfileKeyHash] to
 * null, or what a test stands in for any of them.
 */
class GroupMembershipChanger internal constructor(
    private val userUuid: () -> String?,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val contacts: suspend () -> List<String>,
    private val blocked: suspend () -> List<String>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val consumeKeyPackage: suspend (userUuid: String) -> Result<ConsumeKeyPackageDto, NetworkError>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val savePending: suspend (change: PendingMembershipChangeEntity) -> Unit,
    private val bookResend: suspend () -> Unit,
    private val send: suspend (
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ) -> GroupMembershipAnswer,
    private val ownProfileKey: suspend () -> ByteArray?,
    private val contactProfileKey: suspend (userUuid: String) -> ByteArray?,
    private val learnedProfileKey: suspend (userUuid: String) -> ByteArray?,
    private val oweProfileKey: suspend (groupUuid: String) -> Unit,
) {
    constructor(
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        keyPackageApi: KeyPackageApi,
        groupDao: GroupDao,
        groupMembershipSender: GroupMembershipSender,
        profileKeyVault: ProfileKeyVault,
        memberProfileRepository: MemberProfileRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        restoreContacts = contactRepository::restore,
        contacts = contactDao::getUserUuids,
        blocked = identityConflictDao::getBlocked,
        client = mlsIdentityRepository::client,
        consumeKeyPackage = keyPackageApi::consumeKeyPackage,
        group = groupDao::get,
        hasPendingChange = groupDao::hasPendingChange,
        savePending = groupDao::upsertMembershipChange,
        bookResend = groupMembershipSender::bookResend,
        send = groupMembershipSender::send,
        ownProfileKey = { (profileKeyVault.getOrCreateProfileEncryptionKey() as? Result.Success)?.data },
        contactProfileKey = { userUuid -> contactDao.get(userUuid = userUuid)?.profileEncryptionKey },
        learnedProfileKey = memberProfileRepository::keyFor,
        oweProfileKey = { groupUuid ->
            groupDao.setHandedProfileKeyHash(uuid = groupUuid, handedProfileKeyHash = null)
        },
    )

    /**
     * Adds [userUuids], at least one of them, to the group [groupUuid], see [GroupMembershipChanger].
     */
    suspend fun add(groupUuid: String, userUuids: Set<String>): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are added one at least." }

        return withGroupsLock {
            // Once the lock is held: the device may have locked as this waited for it, see above.
            val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupMembershipError.NoSession)
                is Result.Error -> return@withGroupsLock read
            }

            addLocked(selfUuid = selfUuid, groupUuid = groupUuid, userUuids = userUuids)
        }
    }

    /**
     * Removes [userUuids], at least one of them and never the user, from the group [groupUuid],
     * see [GroupMembershipChanger].
     */
    suspend fun remove(
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are removed one at least." }

        return withGroupsLock {
            // Once the lock is held: the device may have locked as this waited for it, see above.
            val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupMembershipError.NoSession)
                is Result.Error -> return@withGroupsLock read
            }

            removeLocked(
                selfUuid = selfUuid,
                groupUuid = groupUuid,
                userUuids = userUuids,
                openClient = ::clientWhileUnlocked,
            )
        }
    }

    /**
     * Removes [userUuids] as [remove] does, for a caller already holding [withGroupsLock], which
     * this does not take: the group sync, which removes from the groups the user created whoever
     * the server no longer lists, see [GroupSync]. To be called under that lock alone, which is not
     * reentrant: [remove] would wait on it for good there.
     *
     * The vault is read as is: on a locked device the read throws, and the sync stops its pass there,
     * telling its caller to try again once the device is unlocked, see [GroupSyncError.DeviceLocked].
     */
    internal suspend fun removeUnderGroupsLock(
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are removed one at least." }

        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        return removeLocked(selfUuid = selfUuid, groupUuid = groupUuid, userUuids = userUuids, openClient = ::clientAsIs)
    }

    private suspend fun addLocked(
        selfUuid: String,
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        // Before the pins are read, as for a creation: until the contacts are back, a contact
        // looks like a stranger, and their pin is not there.
        when (val restored = unlockedOrError { restoreContacts() }) {
            is Result.Success -> restored.data.onError { return Result.Error(GroupMembershipError.Contacts(it)) }
            is Result.Error -> return restored
        }

        val client = when (val opened = clientWhileUnlocked()) {
            is Result.Success -> opened.data
            is Result.Error -> return opened
        }

        val roster = when (val read = settledRosterOf(client = client, mlsGroupId = held.mlsGroupId)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val newMemberUuids = userUuids - roster.memberUuids.toSet()

        if (newMemberUuids.isEmpty()) return Result.Success(MembershipChangeOutcome.MADE)

        // Before anything spends a key package.
        if (roster.memberUuids.size + newMemberUuids.size > GroupLimits.MAX_MEMBERS) {
            return Result.Error(GroupMembershipError.TooManyMembers)
        }

        refusalOf(client = client, userUuids = newMemberUuids)?.let { return Result.Error(it) }

        // Before any key package is asked for, which a device locked by now would spend for
        // nothing, see GroupMembershipChanger.
        val ownKey = when (val read = unlockedOrError { ownProfileKey() }) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val keyPackages = when (
            val fetched = fetchKeyPackages(inviteeUuids = newMemberUuids, consumeKeyPackage = consumeKeyPackage)
        ) {
            is KeyPackageFetch.Fetched -> fetched.keyPackages

            is KeyPackageFetch.Unreachable ->
                return Result.Error(GroupMembershipError.Unreachable(userUuids = fetched.userUuids))

            is KeyPackageFetch.Failed -> return Result.Error(GroupMembershipError.Network(fetched.error))
        }

        // Every member's once the new ones are in, as for a creation: the user's own, unless the
        // vault does not answer, which leaves the group owed it, see below, and the others' as
        // they were handed to the user, see profileKeysToHandOut.
        val profileKeys = profileKeysToHandOut(
            memberUuids = roster.memberUuids + keyPackages.map { it.userUuid },
            selfUuid = selfUuid,
            ownProfileKey = ownKey,
            contactProfileKey = contactProfileKey,
            learnedProfileKey = learnedProfileKey,
        )

        // With no entry of the user's own, the members go on holding whatever key of theirs they
        // held, an older one or none: the group is owed theirs, posted by the sync once the
        // addition is settled, see GroupProfileKeyDelivery. Before anything is built, so that no
        // addition goes out with the group still recorded as holding it; one that never goes out
        // leaves a post owed for nothing, read and changing nothing.
        if (selfUuid !in profileKeys) oweProfileKey(groupUuid)

        // For the members already in, who cannot read the Welcome: in the epoch the Commit leaves,
        // which they read it in whether the server lists it before the Commit or after, see
        // GroupSync.takeProfileKeys. Built before the Commit, once no Commit of the user's is
        // pending, see settledRosterOf. One that never goes out, the Commit failing or refused,
        // leaves nothing behind but a key of the user's spent in the group.
        val profileKeysMessage = try {
            client.encryptApplicationMessage(
                mlsGroupId = held.mlsGroupId,
                payload = profileKeysMessageOf(profileKeys = profileKeys),
            )
        } catch (exception: MlsException) {
            return Result.Error(
                if (exception.isRetryable()) GroupMembershipError.Storage else GroupMembershipError.Crypto,
            )
        }

        return withContext(NonCancellable) {
            val addition = try {
                // The group's key, which its fields are sealed under, as for a creation: the
                // Welcome binds it to this group alone, see GroupSync.
                client.addMembersTo(
                    mlsGroupId = held.mlsGroupId,
                    invitees = keyPackages,
                    welcomePayload = welcomePayloadOf(groupKey = held.group.groupKey, profileKeys = profileKeys),
                )
            } catch (exception: MlsException) {
                return@withContext Result.Error(
                    when {
                        exception is MlsException.IdentityKeyMismatch ->
                            GroupMembershipError.IdentityChanged(userUuids = setOf(exception.v1))

                        exception is MlsException.UnusableKeyPackages ->
                            GroupMembershipError.Unreachable(userUuids = exception.v1.toSet())

                        exception.isRetryable() -> GroupMembershipError.Storage
                        else -> GroupMembershipError.Crypto
                    },
                )
            }

            val change = PendingMembershipChange.Addition(
                groupUuid = groupUuid,
                body = AddGroupMembersBody(
                    nonce = newChangeNonce(),
                    commitMessage = Base64.Default.encode(addition.commit),
                    profileKeysMessage = Base64.Default.encode(profileKeysMessage),
                    welcomeMessage = Base64.Default.encode(addition.welcome),
                    newMemberUuids = keyPackages.map { it.userUuid },
                ),
                commitEpoch = roster.epoch,
            )

            saveAndSend(change = change, held = held, client = client)
        }
    }

    /**
     * Removes [userUuids] from the group [groupUuid] as the user [selfUuid], with the MLS client
     * [openClient] opens, see [clientWhileUnlocked] and [clientAsIs].
     */
    private suspend fun removeLocked(
        selfUuid: String,
        groupUuid: String,
        userUuids: Set<String>,
        openClient: suspend () -> Result<MlsClient, GroupMembershipError>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        // A member's way out is leaving, see GroupLeaver, never a removal of their own, and the
        // creator never leaves: nobody else could change the group.
        require(selfUuid !in userUuids) { "The creator of a group is never removed from it." }

        val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        val client = when (val opened = openClient()) {
            is Result.Success -> opened.data
            is Result.Error -> return opened
        }

        val roster = when (val read = settledRosterOf(client = client, mlsGroupId = held.mlsGroupId)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val removedUuids = userUuids.filter { it in roster.memberUuids }

        if (removedUuids.isEmpty()) return Result.Success(MembershipChangeOutcome.MADE)

        return withContext(NonCancellable) {
            val commit = try {
                client.removeMembersFrom(mlsGroupId = held.mlsGroupId, userUuids = removedUuids)
            } catch (exception: MlsException) {
                return@withContext Result.Error(
                    if (exception.isRetryable()) GroupMembershipError.Storage else GroupMembershipError.Crypto,
                )
            }

            val change = PendingMembershipChange.Removal(
                groupUuid = groupUuid,
                body = RemoveGroupMembersBody(
                    nonce = newChangeNonce(),
                    commitMessage = Base64.Default.encode(commit),
                    removedMemberUuids = removedUuids,
                ),
                commitEpoch = roster.epoch,
            )

            saveAndSend(change = change, held = held, client = client)
        }
    }

    /** The user's MLS client, or why it does not open, the vault read as is, see [removeUnderGroupsLock]. */
    private suspend fun clientAsIs(): Result<MlsClient, GroupMembershipError> =
        when (val opened = client()) {
            is Result.Success -> opened
            is Result.Error -> Result.Error(GroupMembershipError.Identity(opened.error))
        }

    /**
     * The user's MLS client, for a change they asked for, or why it does not open: the device having
     * locked before this process first opened it included, see [GroupMembershipError.DeviceLocked].
     */
    private suspend fun clientWhileUnlocked(): Result<MlsClient, GroupMembershipError> =
        when (val read = unlockedOrError { clientAsIs() }) {
            is Result.Success -> read.data
            is Result.Error -> read
        }

    /**
     * The group [groupUuid] with its MLS group, when the user [selfUuid] may change its members
     * from this device, see [GroupMembershipChanger], or why not.
     */
    private suspend fun changeable(groupUuid: String, selfUuid: String): Result<HeldGroup, GroupMembershipError> {
        val group = group(groupUuid) ?: return Result.Error(GroupMembershipError.NotHeld)

        if (group.ownerUuid != selfUuid) return Result.Error(GroupMembershipError.NotCreator)

        val mlsGroupId = group.mlsGroupId ?: return Result.Error(GroupMembershipError.NotHeld)

        if (hasPendingChange(groupUuid)) return Result.Error(GroupMembershipError.ChangePending)

        return Result.Success(HeldGroup(group = group, mlsGroupId = mlsGroupId))
    }

    /**
     * Who is in the group [mlsGroupId], once a Commit of the user's left pending there is discarded:
     * with no change saved for the group, see [changeable], it is one the process did not live to
     * save, so never sent, see [saveAndSend], and it would keep the group from building another.
     */
    private suspend fun settledRosterOf(
        client: MlsClient,
        mlsGroupId: ByteArray,
    ): Result<GroupRoster, GroupMembershipError> =
        try {
            val roster = client.rosterOf(mlsGroupId = mlsGroupId)

            if (roster.hasPendingCommit) client.discardPendingCommitIn(mlsGroupId = mlsGroupId)

            Result.Success(roster)
        } catch (exception: MlsException) {
            Result.Error(if (exception.isRetryable()) GroupMembershipError.Storage else GroupMembershipError.Crypto)
        }

    /**
     * Why [userUuids] cannot all be added, or null when they can, see [inviteeRefusalOf], as for a
     * creation.
     */
    private suspend fun refusalOf(client: MlsClient, userUuids: Set<String>): GroupMembershipError? =
        when (
            val refusal = client.inviteeRefusalOf(inviteeUuids = userUuids, contacts = contacts, blocked = blocked)
        ) {
            is InviteeRefusal.NotContacts -> GroupMembershipError.NotContacts(userUuids = refusal.userUuids)
            is InviteeRefusal.IdentityChanged -> GroupMembershipError.IdentityChanged(userUuids = refusal.userUuids)
            InviteeRefusal.Storage -> GroupMembershipError.Storage
            null -> null
        }

    /**
     * Saves [change], whose Commit the library just built in the group [held], books its resend
     * and sends it, see [GroupMembershipSender.send].
     *
     * A change that could not be saved has its Commit discarded again, as nothing would ever send
     * it, or by the next change to the group if that fails too, see [settledRosterOf].
     */
    private suspend fun saveAndSend(
        change: PendingMembershipChange,
        held: HeldGroup,
        client: MlsClient,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        try {
            savePending(change.toEntity())
        } catch (_: Exception) {
            try {
                client.discardPendingCommitIn(mlsGroupId = held.mlsGroupId)
            } catch (_: MlsException) {
                // See above.
            }

            return Result.Error(GroupMembershipError.Storage)
        }

        // Before the send, as for a creation: a process not living through it leaves the change
        // to go out again, and the group's next change held back until it does.
        bookResend()

        return when (val answer = send(change, held.group, client)) {
            GroupMembershipAnswer.Made,
            GroupMembershipAnswer.AlreadyMade,
            -> Result.Success(MembershipChangeOutcome.MADE)

            is GroupMembershipAnswer.DeletedMembers ->
                Result.Error(GroupMembershipError.Deleted(userUuids = answer.userUuids.toSet()))

            is GroupMembershipAnswer.Refused -> Result.Error(GroupMembershipError.Network(answer.error))

            is GroupMembershipAnswer.RateLimited,
            is GroupMembershipAnswer.Failed,
            -> Result.Success(MembershipChangeOutcome.PENDING)
        }
    }
}

/** A group the user may change the members of, and its MLS group, see [GroupMembershipChanger.changeable]. */
private class HeldGroup(val group: GroupEntity, val mlsGroupId: ByteArray)

/**
 * What [read], a read of the vault for a change the user asked for, gives, or
 * [GroupMembershipError.DeviceLocked] when the device is locked: what the vault holds is read with
 * keys a locked device cannot use, and reading it throws an [IllegalStateException] then.
 */
private inline fun <T> unlockedOrError(read: () -> T): Result<T, GroupMembershipError> =
    try {
        Result.Success(read())
    } catch (exception: CancellationException) {
        // The caller going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        Result.Error(GroupMembershipError.DeviceLocked)
    }
