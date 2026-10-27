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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.GroupRoster
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.addMembersTo
import mls.discardPendingCommitIn
import mls.isRetryable
import mls.removeMembersFrom
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
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
 *    group's key, and saved with the request that will make it, see
 *    [PendingMembershipChangeEntity], before anything is sent.
 * 4. It is sent, see [GroupMembershipSender.send], its resend booked first, see
 *    [GroupMembershipSender.bookResend]. Only a refusal undoes it; the server not answering, or
 *    answering that it cannot take it yet, leaves it to go out again, see
 *    [MembershipChangeOutcome.PENDING]. A removal taken is followed by the rotation of the group's
 *    key, which the sender sees to.
 *
 * Everything that can fail without leaving anything behind comes before the Commit is built. From
 * there to the answer is not cancellable: a Commit built and never saved would hold the group's
 * next change back, and a change sent and left there would wait for the next sync to reach its
 * members, a removal its key rotation.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [restoreContacts] is [ContactRepository.restore],
 * [contacts] and [blocked] are [ContactDao.getUserUuids] and [IdentityConflictDao.getBlocked],
 * [client] is [MlsIdentityRepository.client], [consumeKeyPackage] is
 * [KeyPackageApi.consumeKeyPackage], [group] is [GroupDao.get], [hasPendingChange] is
 * [GroupDao.hasPendingChange], [savePending] is [GroupDao.upsertMembershipChange], and
 * [bookResend] and [send] are [GroupMembershipSender.bookResend] and [GroupMembershipSender.send],
 * or what a test stands in for any of them.
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
) {
    constructor(
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        keyPackageApi: KeyPackageApi,
        groupDao: GroupDao,
        groupMembershipSender: GroupMembershipSender,
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
    )

    /**
     * Adds [userUuids], at least one of them, to the group [groupUuid], see [GroupMembershipChanger].
     */
    suspend fun add(groupUuid: String, userUuids: Set<String>): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are added one at least." }

        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        return withGroupsLock { addLocked(selfUuid = selfUuid, groupUuid = groupUuid, userUuids = userUuids) }
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

        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        // Leaving is no removal, and the creator never leaves: nobody else could change the group.
        require(selfUuid !in userUuids) { "The creator of a group is never removed from it." }

        return withGroupsLock { removeLocked(selfUuid = selfUuid, groupUuid = groupUuid, userUuids = userUuids) }
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
        restoreContacts().onError { return Result.Error(GroupMembershipError.Contacts(it)) }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupMembershipError.Identity(opened.error))
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

        val keyPackages = when (
            val fetched = fetchKeyPackages(inviteeUuids = newMemberUuids, consumeKeyPackage = consumeKeyPackage)
        ) {
            is KeyPackageFetch.Fetched -> fetched.keyPackages

            is KeyPackageFetch.Unreachable ->
                return Result.Error(GroupMembershipError.Unreachable(userUuids = fetched.userUuids))

            is KeyPackageFetch.Failed -> return Result.Error(GroupMembershipError.Network(fetched.error))
        }

        return withContext(NonCancellable) {
            val addition = try {
                // The group's key, which its fields are sealed under, as for a creation: the
                // Welcome binds it to this group alone, see GroupSync.
                client.addMembersTo(
                    mlsGroupId = held.mlsGroupId,
                    invitees = keyPackages,
                    welcomePayload = held.group.groupKey,
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
                    welcomeMessage = Base64.Default.encode(addition.welcome),
                    newMemberUuids = keyPackages.map { it.userUuid },
                ),
                commitEpoch = roster.epoch,
            )

            saveAndSend(change = change, held = held, client = client)
        }
    }

    private suspend fun removeLocked(
        selfUuid: String,
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupMembershipError.Identity(opened.error))
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
