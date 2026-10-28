package group

import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSyncError
import contact.ContactSyncPass
import database.data.local.dao.ContactDao
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.UserDao
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.repository.ContactRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import message.ResendScheduler
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupOf
import mls.deleteGroupQuietly
import mls.inspectGroupWelcome
import mls.isRetryable
import mls.joinGroupOf
import mls.pinnedSignatureKeyFor
import mls.processGroupMessageIn
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.WelcomeInspection
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
import vault.VaultError
import vault.VaultRepository
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How soon a leave the server was not heard to take is sent again, see [GroupSync.leaveDeclined]:
 * as soon as a change to a group's members is, see [GroupMembershipSender]. Nothing else may bring
 * the sync back for it soon: a group is mostly declined for a creator who is no contact of the
 * user's, whose pushes their phone refuses, see [push.PushSignal].
 */
private const val UNANSWERED_LEAVE_RESEND_SECONDS = 60L

/** A group the user was added to, or added back to, and joined in a [GroupSync] pass, for them to be told about. */
data class GroupArrival(
    val groupUuid: String,
    val title: String,

    /** Who added the user: whoever wrote the Welcome, one of the user's contacts. */
    val adderUuid: String,

    /** Null while this device has yet to read their name. */
    val adderFullName: String?,
)

/** A group whose creator removed the user from it, as a [GroupSync] pass read, for them to be told about. */
data class GroupRemoval(
    val groupUuid: String,
    val title: String,

    /**
     * Who removed the user: the group's creator, the one member who removes anybody, see
     * [GroupEntity.ownerUuid].
     */
    val removerUuid: String,

    /** Null while this device has yet to read their name. */
    val removerFullName: String?,
)

/** What one [GroupSync.sync] came to. */
class GroupSyncPass(
    /** The groups the user was added to and joined, whatever else the pass came to. */
    val joined: List<GroupArrival>,

    /**
     * The groups the user was removed from, whatever else the pass came to. Each group is in one of
     * the two at most, as the pass left it: one the user was removed from and added back to is
     * among [joined] alone, and one they were added to and removed from again among these alone.
     */
    val removed: List<GroupRemoval>,

    /**
     * The first thing that kept the pass from doing all it had to, or null when nothing did, but
     * [GroupSyncError.DeviceLocked] whenever the device locked as the pass ran, whatever came
     * before: the rest of the pass waits for an unlock rather than a retry.
     */
    val failure: GroupSyncError?,
)

/**
 * Brings the user's groups on this device in line with what the server holds, on every side of a
 * group: the creator's, whose creation, or change to its members, may not have reached the server
 * yet, and the members', who join it from its Welcome and follow what its creator changes of it.
 *
 * In this order, under [withGroupsLock] as a creation and a change to a group's members are, since
 * they all read and write the same:
 * 1. Every creation the server has not been heard to take is sent again, see
 *    [GroupCreationSender.resendPending], and so is every change to a group's members, see
 *    [GroupMembershipSender.resendPending].
 * 2. Every group key the vault does not hold yet is backed up, see [GroupKeyBackUp.backUpPending],
 *    the one a key rotation the server just took hands over among them.
 * 3. What waits in every group this device holds the MLS state of and the user is still in is
 *    read, see [drain], whether or not the server still lists the group: the server lists a group
 *    to its current members alone, and the Commit removing the user is how this device learns it.
 * 4. Every group the user created that the server lists has whoever it no longer lists of its
 *    members removed from it, see [dropDeparted]: those who left it, as a device declining it
 *    does, and accounts deleted since.
 * 5. Every group brought back after a log-out that the server lists takes the members it lists of
 *    it, see [refresh], and every one it no longer lists is one the user was removed from, see
 *    [leaveUnlisted]: there is no MLS state to read the Commits doing so with.
 * 6. Every group the server lists that this device neither holds nor declined is taken in, see
 *    [takeIn]: brought back from the key the vault kept of it, after a log-out took it off this
 *    device, or else joined from the Welcome waiting there, or declined. One with neither is
 *    declined too, nothing being left that could ever open it here. One whose fields the kept key
 *    no longer opens, its creator having rotated the key since, is neither brought back nor
 *    declined: it is joined only from the Welcome of the owner the server lists adding the user
 *    back, see [restore] and [rejoin], what waits there until then acknowledged unread, and every
 *    sync tries it again. Every group the user was removed from that the server lists again is
 *    taken in as well, joined again from the Welcome of its creator adding them back, see
 *    [rejoin], what waits before it acknowledged with it, and so is every group brought back after
 *    a log-out, which only that gives MLS state again, what waits there with no such Welcome
 *    acknowledged unread.
 * 7. Every group this device declined is left on the server, those this pass declined included,
 *    see [leaveDeclined].
 * 8. Once every group is caught up, the key to the user's own profile is posted into each owed
 *    it: each they joined, or whose creator seated somebody, since they last posted it there, see
 *    [takeProfileKeys], and each once their key rotates, see [GroupProfileKeyDelivery].
 *
 * Then, with the groups free again, the names of the members who are not the user's contacts are
 * read with the keys the groups handed over, and the keys and names backed up, see
 * [MemberProfileRepository.readNames] and [MemberProfileRepository.backUp]: a request per
 * member, which nothing changing the user's groups need wait on. Those the vault kept are brought
 * back first, once after a log-in, before anything is taken from a group, see
 * [MemberProfileRepository.restore].
 *
 * A group is only ever joined from somebody who may add the user to one, see [mayAdd]: the server
 * lets anybody create a group with anybody in it, and keeps them a member whatever they make of the
 * Welcome, until they leave it. So a group whose Welcome anybody else wrote is declined, see
 * [DeclinedGroupEntity], and left, see [leaveDeclined], which drops its Welcome on the server with
 * everything else that waits there, and everything sent there after. Declined, it holds no key
 * package back from pruning, see [hasNoWelcomeWaiting]. Who wrote it is the Welcome's word, not the
 * server's, read before anything is stored, and so is who is in the group, see [join].
 *
 * Once joined, a group changes by its creator alone, see [GroupEntity.ownerUuid]: their Commits are
 * taken, and the keys they hand over, and nobody else's, see [read]. The keys to the members'
 * profiles are the one thing anybody else hands over: each member their own, which beats the one
 * the creator relayed for them, and any of theirs that came in an earlier message, see
 * [takeProfileKeys].
 *
 * Of a group this device holds the MLS state of, the one thing the server's word decides of its
 * members is who is no longer one: the creator's device removes whoever the server stops listing,
 * see [dropDeparted], and rotates the group's key after them, as for anybody the user removes. A
 * server lying about it can only have the creator remove people, which it can do in effect already
 * by no longer delivering to them, and the rotation leaves them nothing to read the group with from
 * then on. It cannot have anybody added to such a group, nor any member take a Commit from anybody
 * but the creator. A group brought back after a log-out holds no tree to tell its members, so it
 * takes the members and the creator the server lists, additions included, see [restore] and
 * [refresh], until the Welcome of that creator adding the user back gives it MLS state again, see
 * [rejoin]. The owner the server lists also decides which group this device declined is not left,
 * see [leaveDeclined]: a server lying about it keeps the user a member there, which it can do
 * anyway by ignoring the leave.
 *
 * Every step is attempted even when one fails, and the first failure is reported, with the groups
 * joined and left either way, see [GroupSyncPass]: what was joined or left is told about once, by
 * the pass that read it. Whatever did not get done is picked up by the next sync. The device
 * locking stops the pass where it is instead, nothing this device stores being readable then, and
 * the groups joined and left until that point are reported all the same, see
 * [GroupSyncError.DeviceLocked].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [resendPending] is [GroupCreationSender.resendPending],
 * [resendMembershipChanges] is [GroupMembershipSender.resendPending], [backUpPending] and [backUp]
 * are those of [GroupKeyBackUp], [restoreContacts] is [ContactRepository.restore], [listGroups],
 * [listMessages], [acknowledge] and [leave] are [GroupApi.getGroups], [GroupApi.getGroupMessages],
 * [GroupApi.acknowledgeGroupMessages] and [GroupApi.leave], [storedGroups], [declinedGroups],
 * [save], [memberUuidsOf], [replaceMembers], [updateKey], [markLeft], [recordDeclined] and
 * [forgetDeclined] are [GroupDao.getAll], [GroupDao.getDeclinedUuids], [GroupDao.upsertWithMembers],
 * [GroupDao.getMemberUuids], [GroupDao.replaceMembers], [GroupDao.updateGroupKey],
 * [GroupDao.markLeft], [GroupDao.insertDeclined] and [GroupDao.deleteDeclined], [removeDeparted]
 * is [GroupMembershipChanger.removeUnderGroupsLock], [vaultKeys] is [VaultRepository.getGroupKeys],
 * [isUnblockedContact] is [ContactDao.isUnblocked], [client] is [MlsIdentityRepository.client],
 * [fullName] is [UserDao.getFullName], [hasPendingChange] is [GroupDao.hasPendingChange],
 * [isBlocked] is [IdentityConflictDao.isBlocked], [setHandedProfileKey] is
 * [GroupDao.setHandedProfileKeyHash], [takeOwnerKey], [fillGap], [restoreMemberProfiles],
 * [readMemberNames] and [backUpMemberProfiles] are [MemberProfileRepository.takeOwnerKey],
 * [MemberProfileRepository.fillGap], [MemberProfileRepository.restore],
 * [MemberProfileRepository.readNames] and [MemberProfileRepository.backUp], and
 * [handOutProfileKey] is [GroupProfileKeyDelivery.handOut], or what a test stands in for any of
 * them, as [resendScheduler] is for the scheduler bound on this platform.
 */
class GroupSync internal constructor(
    private val userUuid: () -> String?,
    private val resendPending: suspend () -> EmptyResult<NetworkError>,
    private val resendMembershipChanges: suspend () -> EmptyResult<GroupSyncError>,
    private val backUpPending: suspend () -> EmptyResult<VaultError>,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val storedGroups: suspend () -> List<GroupEntity>,
    private val declinedGroups: suspend () -> List<String>,
    private val vaultKeys: suspend () -> Result<Map<String, ByteArray>, VaultError>,
    private val listMessages: suspend (groupUuid: String) -> Result<ListGroupMessagesResponse, NetworkError>,
    private val acknowledge: suspend (groupUuid: String, messageUuids: List<String>) -> EmptyResult<NetworkError>,
    private val leave: suspend (groupUuid: String) -> GroupLeaveAnswer,
    private val isUnblockedContact: suspend (userUuid: String) -> Boolean,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val save: suspend (group: GroupEntity, members: List<GroupMemberEntity>) -> Unit,
    private val memberUuidsOf: suspend (groupUuid: String) -> List<String>,
    private val replaceMembers: suspend (groupUuid: String, members: List<GroupMemberEntity>) -> Unit,
    private val updateKey: suspend (groupUuid: String, groupKey: ByteArray) -> Unit,
    private val markLeft: suspend (groupUuid: String) -> Unit,
    private val recordDeclined: suspend (groupUuid: String) -> Unit,
    private val forgetDeclined: suspend (groupUuid: String) -> Unit,
    private val removeDeparted: suspend (
        groupUuid: String,
        userUuids: Set<String>,
    ) -> Result<MembershipChangeOutcome, GroupMembershipError>,
    private val backUp: suspend (groupUuid: String, groupKey: ByteArray) -> EmptyResult<VaultError>,
    private val fullName: suspend (userUuid: String) -> String?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val isBlocked: suspend (userUuid: String) -> Boolean,
    private val setHandedProfileKey: suspend (groupUuid: String, handedProfileKeyHash: String?) -> Unit,
    private val takeOwnerKey: suspend (userUuid: String, profileEncryptionKey: ByteArray, sentAt: Long) -> Unit,
    private val fillGap: suspend (userUuid: String, profileEncryptionKey: ByteArray) -> Unit,
    private val restoreMemberProfiles: suspend () -> EmptyResult<GroupSyncError>,
    private val readMemberNames: suspend () -> EmptyResult<GroupSyncError>,
    private val backUpMemberProfiles: suspend () -> EmptyResult<GroupSyncError>,
    private val handOutProfileKey: suspend (behind: Set<String>) -> EmptyResult<GroupSyncError>,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupCreationSender: GroupCreationSender,
        groupMembershipSender: GroupMembershipSender,
        groupMembershipChanger: GroupMembershipChanger,
        groupKeyBackUp: GroupKeyBackUp,
        groupProfileKeyDelivery: GroupProfileKeyDelivery,
        memberProfileRepository: MemberProfileRepository,
        vaultRepository: VaultRepository,
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        userDao: UserDao,
        resendScheduler: ResendScheduler,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        resendPending = groupCreationSender::resendPending,
        resendMembershipChanges = groupMembershipSender::resendPending,
        backUpPending = groupKeyBackUp::backUpPending,
        restoreContacts = contactRepository::restore,
        listGroups = groupApi::getGroups,
        storedGroups = groupDao::getAll,
        declinedGroups = groupDao::getDeclinedUuids,
        vaultKeys = vaultRepository::getGroupKeys,
        listMessages = groupApi::getGroupMessages,
        acknowledge = groupApi::acknowledgeGroupMessages,
        leave = groupApi::leave,
        isUnblockedContact = contactDao::isUnblocked,
        client = mlsIdentityRepository::client,
        save = groupDao::upsertWithMembers,
        memberUuidsOf = groupDao::getMemberUuids,
        replaceMembers = groupDao::replaceMembers,
        updateKey = { groupUuid, groupKey -> groupDao.updateGroupKey(uuid = groupUuid, groupKey = groupKey) },
        markLeft = groupDao::markLeft,
        recordDeclined = { groupUuid -> groupDao.insertDeclined(DeclinedGroupEntity(groupUuid = groupUuid)) },
        forgetDeclined = groupDao::deleteDeclined,
        removeDeparted = groupMembershipChanger::removeUnderGroupsLock,
        backUp = groupKeyBackUp::backUp,
        fullName = userDao::getFullName,
        hasPendingChange = groupDao::hasPendingChange,
        isBlocked = identityConflictDao::isBlocked,
        setHandedProfileKey = { groupUuid, handedProfileKeyHash ->
            groupDao.setHandedProfileKeyHash(uuid = groupUuid, handedProfileKeyHash = handedProfileKeyHash)
        },
        takeOwnerKey = memberProfileRepository::takeOwnerKey,
        fillGap = memberProfileRepository::fillGap,
        restoreMemberProfiles = memberProfileRepository::restore,
        readMemberNames = memberProfileRepository::readNames,
        backUpMemberProfiles = memberProfileRepository::backUp,
        handOutProfileKey = groupProfileKeyDelivery::handOut,
        resendScheduler = resendScheduler,
    )

    /**
     * Runs every step, see [GroupSync], and says which groups it joined and left, and what failed
     * first.
     *
     * [contactsTakenIn] is whether the contact sync run just before took in all the server held,
     * see [ContactSyncPass.isTakenIn]. Until one has, somebody who is not a contact on this device
     * may be one it has yet to hear of, so their Welcome is left waiting rather than declined, see
     * [join], and not reported either: the contact sync reports its own failure.
     *
     * The groups joined and left are reported however far the pass got, the device locking as it
     * ran included, see [GroupSyncError.DeviceLocked]: each is read once, recorded before anything
     * that could throw, and the next pass finds it joined, or left, already, with nothing to tell.
     */
    suspend fun sync(contactsTakenIn: Boolean): GroupSyncPass {
        val joined = mutableListOf<GroupArrival>()
        val removed = mutableListOf<GroupRemoval>()
        var failure: GroupSyncError? = null

        val isRunThrough = runsUnlocked {
            withGroupsLock {
                resendPending().onError { failure = failure ?: GroupSyncError.Network(it) }

                // Before the keys are backed up: a key rotation the server takes now gives its
                // group a key the vault does not hold yet.
                resendMembershipChanges().onError { failure = failure ?: it }

                backUpPending().onError { failure = failure ?: GroupSyncError.Vault(it) }

                bringInLine(joined = joined, removed = removed, contactsTakenIn = contactsTakenIn)
                    ?.let { failure = failure ?: it }
            }
        } && runsUnlocked {
            // Once the groups handed over what they had: the names are read with the keys they
            // hold now, and backed up with them.
            readMemberNames().onError { failure = failure ?: it }

            backUpMemberProfiles().onError { failure = failure ?: it }
        }

        return GroupSyncPass(
            joined = joined.toList(),
            removed = removed.toList(),
            failure = if (isRunThrough) failure else GroupSyncError.DeviceLocked,
        )
    }

    /**
     * Whether no group the server lists for the user holds a Welcome this device has yet to take
     * in. One could need the secret of one of the user's key packages, see
     * [mls.KeyPackageRepository]. False when the server could not be asked.
     *
     * Only the groups this device neither holds nor declined, those the user was removed from,
     * which the server lists again once their creator added the user back, the Welcome doing so
     * waiting there, and those brought back after a log-out, which the creator removing the user
     * and adding them back lets in again the same way: what waits in the others was taken in
     * already, the Welcome joined or declined, see [join], and what came after it needs no key
     * package to be read.
     *
     * Waits for a [sync] already running, whose Welcomes would otherwise still be listed. Only
     * looks: taking them in is [sync]'s.
     */
    suspend fun hasNoWelcomeWaiting(): Boolean = withGroupsLock {
        val listed = when (val list = listGroups()) {
            is Result.Success -> list.data
            is Result.Error -> return@withGroupsLock false
        }

        val known = storedGroups()
            .filter { !it.isLeft && it.mlsGroupId != null }
            .mapTo(HashSet()) { it.uuid } + declinedGroups()

        listed.filterNot { it.uuid in known }.all { group ->
            val waiting = listMessages(group.uuid)

            waiting is Result.Success && waiting.data.messages.isEmpty()
        }
    }

    /**
     * Reads what waits in the user's groups, brings those the server lists in line with it, and
     * takes in those it lists that are new to this device, see [GroupSync], noting in [joined] and
     * [removed] the groups the user was added to and removed from, then leaves those this device
     * declined, posts the user's own profile key into the groups owed it, see
     * [GroupProfileKeyDelivery], and returns the first failure: a group that fails is left for the
     * next sync, and the next group is seen to all the same.
     */
    private suspend fun bringInLine(
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val selfUuid = userUuid() ?: return GroupSyncError.NoSession

        // Before anybody is taken for a stranger: until the contacts are back after a log-out,
        // every one of them looks like one, and a group of theirs would be declined for good.
        restoreContacts().onError { return GroupSyncError.Contacts(it) }

        // Before any key is taken from a group: one a group relays now, kept, would keep out the
        // one its owner handed over that the vault holds, see MemberProfileRepository.fillGap.
        restoreMemberProfiles().onError { return it }

        val listed = when (val list = listGroups()) {
            is Result.Success -> list.data
            is Result.Error -> return GroupSyncError.Network(list.error)
        }

        // The groups this pass leaves short of where their members are, which nothing is posted into.
        val behind = mutableSetOf<String>()

        var failure = drainAll(selfUuid = selfUuid, removed = removed, behind = behind)

        // Read again: the drain took the user out of some.
        val stored = storedGroups().associateBy { it.uuid }

        // Once the creator read what the members posted, in the epoch they posted it in: the
        // removal takes the group past it.
        dropDeparted(listed = listed, stored = stored, selfUuid = selfUuid, behind = behind)
            ?.let { failure = failure ?: it }

        refresh(listed = listed, stored = stored)

        leaveUnlisted(listed = listed, stored = stored, removed = removed)

        takeIn(
            listed = listed,
            stored = stored,
            selfUuid = selfUuid,
            joined = joined,
            removed = removed,
            behind = behind,
            contactsTakenIn = contactsTakenIn,
        )?.let { failure = failure ?: it }

        // Once the groups are taken in, so that one declined just now is left by this very pass.
        leaveDeclined(listed = listed, selfUuid = selfUuid)?.let { failure = failure ?: it }

        // Once every group is caught up, so that each post goes out in the epoch its members are in.
        handOutProfileKey(behind).onError { failure = failure ?: it }

        return failure
    }

    /**
     * Reads what waits in every group this device holds the MLS state of and the user is still in,
     * see [drain], noting in [removed] the groups the user was removed from, and in [behind] those
     * it could not catch up with, and returns the first failure: a group that fails is left for the
     * next sync, and the next group is drained all the same. What waits in one brought back after a
     * log-out is left to [rejoin], which looks there for the Welcome of its creator adding the user
     * back.
     *
     * Nor is a group read while a change of the user's to it waits on the server, see
     * [GroupMembershipSender]: what its members post in the epoch the change takes it to, once the
     * server holds it, reads only once this device applied it too, and read before, it would be
     * refused for good, see [refusalOf]. It waits for a sync that settles the change, which the
     * sync tries before anything else, see [sync]. Only the creator ever has one pending. It is
     * noted in [behind] all the same: the user's key posted into it now would go out in the epoch
     * the change takes it from, which those it seats cannot read.
     */
    private suspend fun drainAll(
        selfUuid: String,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
    ): GroupSyncError? {
        var failure: GroupSyncError? = null

        for (group in storedGroups().filterNot { it.isLeft }) {
            val mlsGroupId = group.mlsGroupId ?: continue

            if (hasPendingChange(group.uuid)) {
                behind += group.uuid

                continue
            }

            // Between groups, never inside one: a message read is consumed, and one whose reading
            // is not acted on is lost.
            currentCoroutineContext().ensureActive()

            val drained = withContext(NonCancellable) {
                drain(group = group, mlsGroupId = mlsGroupId, selfUuid = selfUuid, removed = removed, behind = behind)
            }

            failure = failure ?: drained
        }

        return failure
    }

    /**
     * Reads what waits for the user in [group], whose MLS group [mlsGroupId] this device holds, in
     * the order the server lists it, see [readAll], and acts on what that came to: the user removed,
     * which leaves the group on this device read-only, its MLS state deleted, see
     * [GroupEntity.isLeft], and is noted in [removed]; or else the members the last Commit left,
     * the keys to the members' profiles handed over, see [takeProfileKeys], and the last key the
     * creator handed over, then backed up. One whose messages could not all be read is noted in
     * [behind].
     *
     * The removal is recorded before anything that could throw: the Commit is spent once read, and
     * one whose reading is lost would leave the group held for good, the user never told. Nor is the
     * key of a group the user is no longer in backed up: a vault write, which throws once the device
     * locks. Nor is any key to a member's profile taken from it: a group the user is no longer in
     * vouches for nobody, see [takeProfileKeys].
     *
     * What was read, or refused for good, is acknowledged once acted on. Not cancellable, which the
     * caller sees to: a message read is consumed, and one whose reading is not acted on is lost.
     */
    private suspend fun drain(
        group: GroupEntity,
        mlsGroupId: ByteArray,
        selfUuid: String,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
    ): GroupSyncError? {
        val messages = when (val list = listMessages(group.uuid)) {
            is Result.Success -> list.data.messages
            is Result.Error -> {
                behind += group.uuid

                return GroupSyncError.Network(list.error)
            }
        }

        // Most groups have nothing waiting, most of the time.
        if (messages.isEmpty()) return null

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> {
                behind += group.uuid

                return GroupSyncError.Identity(opened.error)
            }
        }

        val reading = readAll(
            client = client,
            mlsGroupId = mlsGroupId,
            creatorUuid = group.ownerUuid,
            memberUuids = memberUuidsOf(group.uuid),
            messages = messages,
        )

        if (reading.failure != null) behind += group.uuid

        var failure = reading.failure

        if (reading.isRemoved) {
            // The row first, which keeps the group's id: whatever becomes of its state, a direct
            // message naming it is never read, see [GroupEntity.mlsGroupId]. A state the deletion
            // leaves held is deleted again before the user is let back in, see [rejoin].
            markLeft(group.uuid)

            client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

            removed += GroupRemoval(
                groupUuid = group.uuid,
                title = group.title,
                removerUuid = group.ownerUuid,
                removerFullName = fullName(group.ownerUuid),
            )
        } else {
            reading.memberUuids?.let { memberUuids ->
                replaceMembers(
                    group.uuid,
                    membersOf(groupUuid = group.uuid, memberUuids = memberUuids, creatorUuid = group.ownerUuid),
                )
            }

            takeProfileKeys(
                groupUuid = group.uuid,
                creatorUuid = group.ownerUuid,
                selfUuid = selfUuid,
                handedOver = reading.profileKeys,
                memberUuids = reading.memberUuidsSeen,
            )

            reading.groupKeys.lastOrNull()?.let { groupKey ->
                // Recorded as not backed up, so one the vault would not take now, the next sync backs up.
                updateKey(group.uuid, groupKey)

                backUp(group.uuid, groupKey).onError { failure = failure ?: GroupSyncError.Vault(it) }
            }
        }

        // One whose acknowledgement does not go through is read again by the next sync, and
        // refused then, the key it was read with spent: acknowledged as a message that does not read.
        // Or once the user is removed, acknowledged with the Welcome adding them back, see [rejoin].
        if (reading.handled.isNotEmpty()) {
            acknowledge(group.uuid, reading.handled).onError { failure = failure ?: GroupSyncError.Network(it) }
        }

        return failure
    }

    /**
     * Reads [messages], waiting for the user in the MLS group [mlsGroupId] whose creator is
     * [creatorUuid] and whose members are [memberUuids] before the first of them, one after the
     * other in the order the server lists them, see [read]: up to a Commit removing the user, after
     * which there is nothing left to read them with, or up to one that cannot be read yet, which
     * stops the group there, everything after it waiting behind it.
     */
    private suspend fun readAll(
        client: MlsClient,
        mlsGroupId: ByteArray,
        creatorUuid: String,
        memberUuids: List<String>,
        messages: List<GroupMessageDto>,
    ): GroupReading {
        val handled = mutableListOf<String>()
        var membersAfter: List<String>? = null
        val membersSeen = memberUuids.toHashSet()
        val groupKeys = mutableListOf<ByteArray>()
        val profileKeys = mutableListOf<ProfileKeyHandover>()

        for (message in messages) {
            val reading = read(client = client, mlsGroupId = mlsGroupId, creatorUuid = creatorUuid, message = message)

            when (reading) {
                is GroupMessageReading.Committed -> {
                    membersAfter = reading.memberUuids
                    membersSeen += reading.memberUuids
                }

                is GroupMessageReading.Rekeyed -> groupKeys += reading.groupKey

                is GroupMessageReading.HandedProfileKeys -> profileKeys += reading.handover

                GroupMessageReading.Ignored -> Unit

                GroupMessageReading.Removed -> {
                    handled += message.uuid

                    return GroupReading(
                        handled = handled,
                        memberUuids = membersAfter,
                        memberUuidsSeen = membersSeen,
                        groupKeys = groupKeys,
                        profileKeys = profileKeys,
                        isRemoved = true,
                        failure = null,
                    )
                }

                is GroupMessageReading.Waiting -> return GroupReading(
                    handled = handled,
                    memberUuids = membersAfter,
                    memberUuidsSeen = membersSeen,
                    groupKeys = groupKeys,
                    profileKeys = profileKeys,
                    isRemoved = false,
                    failure = reading.failure,
                )
            }

            handled += message.uuid
        }

        return GroupReading(
            handled = handled,
            memberUuids = membersAfter,
            memberUuidsSeen = membersSeen,
            groupKeys = groupKeys,
            profileKeys = profileKeys,
            isRemoved = false,
            failure = null,
        )
    }

    /**
     * What [message], waiting for the user in the MLS group [mlsGroupId], comes to, see
     * [GroupMessageReading]: the creator, [creatorUuid], is the one member whose Commits are taken,
     * see [processGroupMessageIn], and whose application messages hand over the group's key, see
     * [groupKeyOf], and the keys to every member's profile, see [profileKeysOf]. Any member can
     * send one, and the one thing taken from anybody else is the key to their own profile, see
     * [ownProfileKeyOf]: whatever else they send, or the creator sends, is of no use to the user.
     */
    private suspend fun read(
        client: MlsClient,
        mlsGroupId: ByteArray,
        creatorUuid: String,
        message: GroupMessageDto,
    ): GroupMessageReading {
        // The server stores a message without reading it, so this is whatever its sender posted.
        val ciphertext = runCatching { Base64.Default.decode(message.ciphertext) }.getOrNull()
            ?: return GroupMessageReading.Ignored

        val processed = try {
            client.processGroupMessageIn(mlsGroupId = mlsGroupId, message = ciphertext, creatorUuid = creatorUuid)
        } catch (exception: MlsException) {
            return refusalOf(exception = exception)
        }

        return when (val content = processed.processed) {
            is ProcessedMessage.Commit -> if (content.removedSelf) {
                GroupMessageReading.Removed
            } else {
                GroupMessageReading.Committed(
                    memberUuids = checkNotNull(processed.memberUuidsAfterCommit) {
                        "The members are read after every Commit keeping the user in the group."
                    },
                )
            }

            is ProcessedMessage.ApplicationMessage ->
                readingOf(message = content, creatorUuid = creatorUuid, sentAt = message.sentAt())

            // No Proposal gets this far, see processGroupMessageIn, and what is left carries nothing.
            is ProcessedMessage.Proposal, ProcessedMessage.Ignored -> GroupMessageReading.Ignored
        }
    }

    /**
     * What the application [message] read in a group whose creator is [creatorUuid], which the
     * server stored at [sentAt], in milliseconds since the epoch, comes to, see [read]: whose
     * message it is, is who MLS says sent it, never the server's word.
     */
    private fun readingOf(
        message: ProcessedMessage.ApplicationMessage,
        creatorUuid: String,
        sentAt: Long,
    ): GroupMessageReading {
        if (message.senderUserUuid == creatorUuid) {
            groupKeyOf(payload = message.payload)?.let { return GroupMessageReading.Rekeyed(groupKey = it) }

            profileKeysOf(payload = message.payload)?.let {
                return GroupMessageReading.HandedProfileKeys(
                    ProfileKeyHandover.Relayed(profileKeys = it, sentAt = sentAt),
                )
            }
        }

        return ownProfileKeyOf(payload = message.payload)
            ?.let {
                GroupMessageReading.HandedProfileKeys(
                    ProfileKeyHandover.Own(ownerUuid = message.senderUserUuid, profileKey = it, sentAt = sentAt),
                )
            }
            ?: GroupMessageReading.Ignored
    }

    /** What a message the library refused with [exception] comes to. */
    private fun refusalOf(exception: MlsException): GroupMessageReading = when {
        // Says nothing about the message, which is read again later.
        exception.isRetryable() -> GroupMessageReading.Waiting(failure = GroupSyncError.Storage)

        // A Commit of the creator's seating somebody this device pinned to another identity: taken
        // in, it would have the user trust whoever the server served for them, and every later
        // Commit builds on it.
        exception is MlsException.IdentityKeyMismatch ->
            GroupMessageReading.Waiting(failure = GroupSyncError.IdentityChanged)

        // A change from anybody but the creator, see MlsException.UnauthorizedChange, or a message
        // that is not one, or not to this group, or for an epoch long gone, which never reads. Or
        // one too large to take in, see MlsException.GroupStateTooLarge. Each is refused for good,
        // whoever sent it, a member's key to their profile included, of use on the creator's
        // device as on any other: no group is read while a Commit of the user's is pending in it,
        // see [drainAll], which a message from the epoch it leads to would be refused for want
        // of, and whose settling could make room for one too large.
        else -> GroupMessageReading.Ignored
    }

    /**
     * Takes the keys to the members' profiles [handedOver] in the group [groupUuid], whose creator
     * is [creatorUuid], for the user [selfUuid], in the order they were handed over, see
     * [ProfileKeyHandover]: a member's own, from them, replacing whatever is held for them but a
     * key of theirs that came in a message stored no earlier, see
     * [MemberProfileRepository.takeOwnerKey], and the creator's map of the keys he holds, which
     * fills in for members nothing is held for, see [MemberProfileRepository.fillGap], but for his
     * own entry, which is his own key, from the message or the Welcome the map came in. A key
     * relayed never replaces one held, so neither comes before the other.
     *
     * A key from its owner is ranked by when the server stored the message it came in, see
     * [ProfileKeyHandover.sentAt], rather than by when this device read it: the groups are read
     * one after the other, in whatever order the database gives them, see [drainAll], and one a
     * pass could not catch up with is read by a later one. So a member's older post, in a group
     * they were removed from before they posted their new key there, can be read after their newer
     * one in another group, and would win otherwise. A member only ever posts the key they hold,
     * so the later message carries the newer key. The time is the server's word, as the order it
     * lists the messages in is: all it can do with it is keep the newer key from this device,
     * which it can do anyway by holding that post back.
     *
     * The creator's map comes as he seats somebody, and leaves the group owed the user's own key,
     * see [GroupEntity.handedProfileKeyHash], whatever it holds for them. Those he seats can hold
     * an older key of the user's, learned in a group the user has since left, or those already in
     * can, the user's last post having missed them, and the key he relays fills no gap for them:
     * only the user's own post replaces it, see [GroupProfileKeyDelivery]. So every member posts
     * theirs once into each group they join, and once more each time its creator seats somebody.
     * The user's own entry is of no use to them, their key not being this device's to learn.
     *
     * Every key is for somebody in the group: one naming anybody but [memberUuids], whom the group
     * held at one point or another of what was read, is dropped. The map comes with the Commit
     * adding the members it names, the server listing it first, see [GroupMembershipChanger], so
     * who was in the group at the point it was read is not enough.
     *
     * Nothing is taken from a group that cannot vouch for its members, its creator, who seated
     * them all, blocked: what MLS says of who sent what there is that creator's word. Nor from a
     * group the user is no longer in, nor from one this device holds no MLS state for, which the
     * callers see to: neither reads anything.
     *
     * Nothing here reads the vault, which throws once the device locks, see
     * [GroupSyncError.DeviceLocked]: this comes after the messages are read, which spends them, and
     * before they are acknowledged, and whatever they handed over would be lost with them.
     */
    private suspend fun takeProfileKeys(
        groupUuid: String,
        creatorUuid: String,
        selfUuid: String,
        handedOver: List<ProfileKeyHandover>,
        memberUuids: Set<String>,
    ) {
        // Most passes hand nothing over, and the check is a read of its own.
        if (handedOver.isEmpty() || isBlocked(creatorUuid)) return

        for (handover in handedOver) {
            when (handover) {
                is ProfileKeyHandover.Own -> if (handover.ownerUuid in memberUuids) {
                    takeOwnerKey(handover.ownerUuid, handover.profileKey, handover.sentAt)
                }

                is ProfileKeyHandover.Relayed -> handover.profileKeys.forEach { (userUuid, profileKey) ->
                    when (userUuid) {
                        selfUuid -> Unit
                        !in memberUuids -> Unit
                        creatorUuid -> takeOwnerKey(userUuid, profileKey, handover.sentAt)
                        else -> fillGap(userUuid, profileKey)
                    }
                }
            }
        }

        if (handedOver.any { it is ProfileKeyHandover.Relayed }) setHandedProfileKey(groupUuid, null)
    }

    /**
     * Removes from each group the user created, of those this device holds in [stored], whoever
     * its tree holds that the server no longer lists of its members in [listed], all of them in
     * one change to its members, the group's key rotated after it, see [GroupMembershipChanger],
     * and returns the first failure: a group that fails is left for the next sync, which finds the
     * same members gone, and removes them then.
     *
     * Those are members who left the group, see [GroupApi.leave], as a device declining it does,
     * and accounts deleted since, which take nobody off a tree. Left there, a leaf keeps one of the
     * seats a group has, is seated by the Welcome of whoever joins next, and every Commit goes on
     * sealing the group's secrets to it, which whoever holds its keys could read.
     *
     * Not a group whose creator, as its Welcome named them, is anybody else, whoever the server
     * lists as its owner, see [GroupEntity.ownerUuid], nor one this device holds no MLS state of,
     * see [restore]: only its creator's device changes a group. Not one the user was removed from
     * either, which a group they created never is, see [GroupMembershipChanger.remove]. Nor one the
     * server does not list, nor any the drain noted in [behind]: every group a change of the user's
     * waits on the server in is among them, see [drainAll], whose members are past the epoch this
     * device is in, or will be, and every one whose messages could not all be read, which a Commit
     * would leave unread for good, in an epoch it takes the group past.
     *
     * A removal the server refuses, or has not taken yet, is acted on as any is, see
     * [GroupMembershipSender]: one not taken stays pending, sent again by every sync until it is,
     * and the group is left alone until then. Nobody is told: the server cannot tell a member who
     * declined the group from one who left it, and neither can this device.
     *
     * Under [withGroupsLock], as the whole sync is, so the removal is made without taking it, see
     * [GroupMembershipChanger.removeUnderGroupsLock].
     */
    private suspend fun dropDeparted(
        listed: List<GroupDto>,
        stored: Map<String, GroupEntity>,
        selfUuid: String,
        behind: Set<String>,
    ): GroupSyncError? {
        val listedMembers = listed.associate { group -> group.uuid to group.members.mapTo(HashSet()) { it.userUuid } }

        val created = stored.values.mapNotNull { group ->
            val mlsGroupId = group.mlsGroupId ?: return@mapNotNull null

            if (group.ownerUuid != selfUuid || group.uuid !in listedMembers || group.uuid in behind) {
                return@mapNotNull null
            }

            group.uuid to mlsGroupId
        }

        // Most users created no group, and opening the client reads the vault.
        if (created.isEmpty()) return null

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        var failure: GroupSyncError? = null

        for ((groupUuid, mlsGroupId) in created) {
            val roster = try {
                client.rosterOf(mlsGroupId = mlsGroupId)
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage

                continue
            }

            // Never the user, whatever the server lists: the creator never leaves.
            val departed = roster.memberUuids.toSet() - listedMembers.getValue(groupUuid) - selfUuid

            // Most groups lose nobody, most of the time.
            if (departed.isEmpty()) continue

            removeDeparted(groupUuid, departed).onError { failure = failure ?: GroupSyncError.Membership(it) }
        }

        return failure
    }

    /**
     * Gives each group brought back after a log-out, of those this device holds in [stored] that
     * the user is still in, the members the server lists of it in [listed], there being no MLS tree
     * on this device to tell them.
     *
     * Its fields stay as they are: nothing changes them on the server once the group is created but
     * a key rotation, which seals them again as they were, see [GroupMembershipSender], under a key
     * a group brought back never reads.
     */
    private suspend fun refresh(listed: List<GroupDto>, stored: Map<String, GroupEntity>) {
        for (listedGroup in listed) {
            val group = stored[listedGroup.uuid]?.takeIf { !it.isLeft && it.mlsGroupId == null } ?: continue

            val memberUuids = listedGroup.members.map { it.userUuid }

            if (memberUuids.toSet() != memberUuidsOf(group.uuid).toSet()) {
                replaceMembers(
                    group.uuid,
                    membersOf(groupUuid = group.uuid, memberUuids = memberUuids, creatorUuid = group.ownerUuid),
                )
            }
        }
    }

    /**
     * Marks as left every group brought back after a log-out, of those this device holds in
     * [stored] that the user is still in, that the server no longer lists in [listed], noting it in
     * [removed].
     *
     * The server lists a group to its current members and its owner alone, so one it no longer
     * lists is one whose creator removed the user, see [GroupEntity.isLeft]. A group this device
     * holds the MLS state of is told so by the Commit doing so, see [drain], which nothing here can
     * read: a group brought back holds none, see [restore], and so cannot read anything sent in it.
     *
     * What waits there is left unacknowledged: [listed] was read as the pass began, so the Welcome
     * of the creator adding the user back may wait behind the removal already, and the next sync
     * joins from it, acknowledging everything up to it, see [rejoin]. Left waiting, it holds no key
     * package back, the server listing the group no more, see [hasNoWelcomeWaiting], and nothing
     * more is sent to a removed member: the Commit removing them lingers at most.
     */
    private suspend fun leaveUnlisted(
        listed: List<GroupDto>,
        stored: Map<String, GroupEntity>,
        removed: MutableList<GroupRemoval>,
    ) {
        val listedUuids = listed.mapTo(HashSet()) { it.uuid }

        for (group in stored.values) {
            if (group.isLeft || group.mlsGroupId != null || group.uuid in listedUuids) continue

            markLeft(group.uuid)

            removed += GroupRemoval(
                groupUuid = group.uuid,
                title = group.title,
                removerUuid = group.ownerUuid,
                removerFullName = fullName(group.ownerUuid),
            )
        }
    }

    /**
     * Takes in every group the server lists, [listed], that this device neither holds, see
     * [stored], nor declined, every one it holds that the user was removed from, and every one
     * brought back after a log-out, see [GroupSync], noting in [joined] those it joined, and in
     * [behind] those whose catch-up stopped short, see [enter], and returns the first failure: a
     * group that fails is left for the next sync, and the next group is taken in all the same.
     */
    private suspend fun takeIn(
        listed: List<GroupDto>,
        stored: Map<String, GroupEntity>,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val declined = declinedGroups().toHashSet()

        val invitations = listed.filter { group ->
            val held = stored[group.uuid]

            group.uuid !in declined && (held == null || held.isLeft || held.mlsGroupId == null)
        }

        // Most syncs find nothing new, and reading the vault is a request of its own.
        if (invitations.isEmpty()) return null

        // Before anything is joined: a group whose key the vault holds was the user's before a
        // log-out, and a Welcome still waiting there was sealed to a key package that log-out
        // took with it. Not for a group this device holds, removed from or brought back already.
        val groupKeys = if (invitations.any { it.uuid !in stored }) {
            when (val read = vaultKeys()) {
                is Result.Success -> read.data
                is Result.Error -> return GroupSyncError.Vault(read.error)
            }
        } else {
            emptyMap()
        }

        var failure: GroupSyncError? = null

        for (group in invitations) {
            // A pass stops between groups, never inside one: a Welcome joined is spent, and a
            // group joined and never saved would be MLS state nothing names.
            currentCoroutineContext().ensureActive()

            val held = stored[group.uuid]
            val groupKey = groupKeys[group.uuid]

            val taken = withContext(NonCancellable) {
                when {
                    held != null -> rejoin(
                        group = group,
                        creatorUuid = held.ownerUuid,
                        heldMlsGroupId = held.mlsGroupId,
                        selfUuid = selfUuid,
                        joined = joined,
                        removed = removed,
                        behind = behind,
                        contactsTakenIn = contactsTakenIn,
                    )

                    groupKey != null -> restore(
                        group = group,
                        groupKey = groupKey,
                        selfUuid = selfUuid,
                        joined = joined,
                        removed = removed,
                        behind = behind,
                        contactsTakenIn = contactsTakenIn,
                    )

                    else -> join(
                        group = group,
                        selfUuid = selfUuid,
                        joined = joined,
                        removed = removed,
                        behind = behind,
                        contactsTakenIn = contactsTakenIn,
                    )
                }
            }

            failure = failure ?: taken
        }

        return failure
    }

    /**
     * Brings back [group], which a log-out took off this device, with [groupKey], the key the vault
     * kept of it: its fields and its members, without its MLS state, which the log-out took for
     * good, see [GroupEntity.mlsGroupId]. The members are those the server lists, the tree that
     * named them having gone with that state, and so they stay, see [refresh].
     *
     * Its creator is the owner the server lists, the Welcome that named them long gone. No change
     * to its members can be made from this device anyway, it holding no MLS state for the group.
     *
     * Fields that do not open under that key, as this group's, are sealed under one the creator
     * handed over since, as they removed somebody, in a message this device can never read, or are
     * not the ones its members sealed. The group is then left where it was, on the server, unless
     * its creator adding the user back lets them in again, see [rejoin]: whatever else waits there
     * while the server lists the group is acknowledged unread, so that it neither holds a key
     * package back from pruning, see [hasNoWelcomeWaiting], nor piles up. One it no longer lists
     * holds none back, and nothing more is sent there, see [leaveUnlisted].
     */
    private suspend fun restore(
        group: GroupDto,
        groupKey: ByteArray,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val fields = group.sealedFields().unseal(groupKey = groupKey, groupUuid = group.uuid)
            ?: return rejoin(
                group = group,
                creatorUuid = group.ownerUuid,
                heldMlsGroupId = null,
                selfUuid = selfUuid,
                joined = joined,
                removed = removed,
                behind = behind,
                contactsTakenIn = contactsTakenIn,
            )

        val kept = keep(
            group = group,
            fields = fields,
            creatorUuid = group.ownerUuid,
            memberUuids = group.members.map { it.userUuid },
            mlsGroupId = null,
            groupKey = groupKey,
            isBackedUp = true,
            isLeft = false,
        )

        return if (kept) null else GroupSyncError.Storage
    }

    /**
     * Joins [group] from the Welcome waiting there, and notes it in [joined]; or declines it, see
     * [decline].
     *
     * The Welcome is read for what it says before anything is stored, see [inspectGroupWelcome],
     * since the join records the group as joined for good, deleted again or not: a Welcome joined
     * only to be refused, or left waiting, would leave the group unjoinable here. The inspection
     * refuses a Welcome to any group but [group], whose MLS id the library derives from its UUID,
     * as for the Welcome posted again under another group's UUID by any of its members; and no
     * channel or contact request can have taken that id. A member can create a group of their own
     * under [group]'s UUID, though, which takes the same id, and post its Welcome there: the
     * Welcome the server wrote as it added the user, the first one waiting, is the one that says
     * who adds them, and only a Welcome of theirs is ever joined from, see [latestWelcome], so a
     * Welcome refused as one already joined is to [group] itself, joined here before. It is
     * declined without being joined unless it holds up: the member who wrote it may add the user
     * to a group, see [mayAdd], whoever the server says sent it, and what they handed over opens
     * this group, see [enter]. When only who wrote it does not hold up, it is left waiting instead
     * unless [contactsTakenIn], see [sync].
     *
     * Joined otherwise, from the last Welcome its creator wrote, that first one unless they removed
     * the user and added them back since, and saved as it stands once caught up with whatever came
     * after it, see [enter], or noted in [removed] instead if that removed the user again.
     *
     * Not cancellable, which the caller sees to: once the join has run, the same Welcome is refused
     * ever after, the group being recorded as joined, and a group joined and not saved is lost.
     */
    private suspend fun join(
        group: GroupDto,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val messages = when (val list = listMessages(group.uuid)) {
            is Result.Success -> list.data.messages
            is Result.Error -> return GroupSyncError.Network(list.error)
        }

        // Written as the user was added, so it comes first: any member can post a Welcome here
        // after it, to a group of their own under this UUID, whose id is this group's. None
        // waiting is a group this device has no way into, ever: the server hands a member no
        // Welcome but those its owner writes adding them, and the vault holds no key to it, as
        // for a group joined before a log-out whose key was never backed up. One declined before
        // a log-out and not left yet still has its Welcome waiting, sealed to a key package that
        // log-out took, which the inspection refuses.
        val welcome = messages.firstOrNull() ?: return decline(groupUuid = group.uuid)

        // The server stores a Welcome without reading it, so this is whatever its sender posted.
        val welcomeMessage = runCatching { Base64.Default.decode(welcome.ciphertext) }.getOrNull()
            ?: return decline(groupUuid = group.uuid)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        val inspection = try {
            client.inspectGroupWelcome(welcome = welcomeMessage, groupUuid = group.uuid)
        } catch (exception: MlsException) {
            // A Welcome that was never ours, was already used, is to another group, seats an
            // identity other than the one pinned for that member or more members than a group may
            // hold, is simply no way in. Storage failing says nothing about the Welcome, so that
            // one is tried again.
            return if (exception.isRetryable()) {
                GroupSyncError.Storage
            } else {
                decline(groupUuid = group.uuid)
            }
        }

        val committerMayAdd = try {
            mayAdd(client = client, userUuid = inspection.committerUserUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        if (!committerMayAdd) {
            // Declining is for good: not while they may be a contact this device has yet to hear of.
            return if (contactsTakenIn) decline(groupUuid = group.uuid) else null
        }

        val latest = try {
            latestWelcome(
                client = client,
                groupUuid = group.uuid,
                creatorUuid = inspection.committerUserUuid,
                messages = messages,
                from = 1,
            )
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        return enter(
            group = group,
            client = client,
            welcome = latest ?: WaitingWelcome(index = 0, message = welcomeMessage, inspection = inspection),
            messages = messages,
            selfUuid = selfUuid,
            joined = joined,
            removed = removed,
            behind = behind,
            refuse = { decline(groupUuid = group.uuid) },
        )
    }

    /**
     * Joins [group] again once its creator, [creatorUuid], added the user back, and notes it in
     * [joined]; or leaves it as it is, see [passOver]. One this device records the user as removed
     * from, whose MLS group was [heldMlsGroupId], or one it holds no MLS state for, brought back
     * after a log-out, see [restore], which being added back is the only way to MLS state again.
     *
     * The way back in is the last Welcome waiting that [creatorUuid] wrote, see [latestWelcome]:
     * whoever this device recorded as the group's creator, see [GroupEntity.ownerUuid], or the
     * owner the server lists for one it does not hold, and they must still be somebody who may add
     * the user to a group, see [mayAdd]. Whatever this device could not acknowledge as it read the
     * Commit removing the user, that Commit included, may still wait before it, and so does
     * whatever was sent in a group with no MLS state to read it with: it is acknowledged with it.
     * What comes after it is caught up with, and the row brought back, with what the Welcome hands
     * over, see [enter].
     *
     * The MLS state of [heldMlsGroupId] is deleted again first, should the removal's deletion not
     * have gone through, see [drain]: held, it would refuse every Welcome to the group, its
     * creator's adding the user back included, see [MlsClient.inspectWelcome].
     *
     * Not cancellable, which the caller sees to, as for [join].
     */
    private suspend fun rejoin(
        group: GroupDto,
        creatorUuid: String,
        heldMlsGroupId: ByteArray?,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val messages = when (val list = listMessages(group.uuid)) {
            is Result.Success -> list.data.messages
            is Result.Error -> return GroupSyncError.Network(list.error)
        }

        // A way back in this device passed over already, or none yet, as for most groups brought back.
        if (messages.isEmpty()) return null

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        // See above. The user's storage failing, the Welcome is left waiting for the next sync.
        heldMlsGroupId?.let { mlsGroupId ->
            try {
                client.deleteGroupOf(mlsGroupId = mlsGroupId)
            } catch (_: MlsException) {
                return GroupSyncError.Storage
            }
        }

        val creatorMayAdd = try {
            mayAdd(client = client, userUuid = creatorUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        if (!creatorMayAdd) {
            // As for a group the user was never in: not while they may be a contact this device
            // has yet to hear of.
            return if (contactsTakenIn) passOver(groupUuid = group.uuid, messages = messages) else null
        }

        val latest = try {
            latestWelcome(
                client = client,
                groupUuid = group.uuid,
                creatorUuid = creatorUuid,
                messages = messages,
                from = 0,
            )
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        } ?: return passOver(groupUuid = group.uuid, messages = messages)

        return enter(
            group = group,
            client = client,
            welcome = latest,
            messages = messages,
            selfUuid = selfUuid,
            joined = joined,
            removed = removed,
            behind = behind,
            refuse = { passOver(groupUuid = group.uuid, messages = messages) },
        )
    }

    /**
     * The last of [messages], from the one at [from] on, that is a Welcome to the group [groupUuid]
     * its creator, [creatorUuid], wrote, see [inspectGroupWelcome], or null when there is none.
     *
     * The creator alone adds anybody, and the server refuses adding a member who is in the group
     * already, so a later Welcome of theirs comes after they removed the user, and lets them in where
     * the group stands now: whatever came before it ended with the user removed, and is of no use to
     * them any more. One anybody else wrote, a member who created a group of their own under its
     * UUID, whose id is this group's, would make the user a member of theirs instead. One of the
     * creator's own from before this device last deleted the group, as it does once the user is
     * removed, is refused by the library, see [MlsClient.inspectWelcome].
     *
     * Throws [MlsException] when the user's storage fails, which says nothing of the messages.
     */
    private suspend fun latestWelcome(
        client: MlsClient,
        groupUuid: String,
        creatorUuid: String,
        messages: List<GroupMessageDto>,
        from: Int,
    ): WaitingWelcome? {
        for (index in messages.lastIndex downTo from) {
            val welcomeMessage = runCatching { Base64.Default.decode(messages[index].ciphertext) }.getOrNull()
                ?: continue

            val inspection = try {
                client.inspectGroupWelcome(welcome = welcomeMessage, groupUuid = groupUuid)
            } catch (exception: MlsException) {
                // No Welcome at all, or no way in: one before it may still be.
                if (exception.isRetryable()) throw exception else continue
            }

            if (inspection.committerUserUuid == creatorUuid) {
                return WaitingWelcome(index = index, message = welcomeMessage, inspection = inspection)
            }
        }

        return null
    }

    /**
     * Joins [group] from [welcome], one of [messages], which its inspection found worth joining,
     * catches up with what waits after it, see [readAll], and saves the group as it stands then,
     * noting it in [joined], or in [removed] when one of those messages removed the user again,
     * and in [behind] when one of them could not be read yet; or [refuse]s the way in when the
     * group's fields open under none of the keys its creator handed over. What waits before the
     * Welcome is acknowledged with it, see [latestWelcome].
     *
     * The fields are those the server lists, sealed under the key the group has now, which a key
     * rotation since the Welcome replaced if the creator removed somebody in between. So they have
     * to open under the key the Welcome hands over or one the creator handed over after it, which
     * only the join lets this device read; when they open under the Welcome's, or nothing waits
     * after it, that is known before anything is joined. The key that opens them, as this group's,
     * see [SealedGroupFields.unseal], is also what refuses a key that is not 32 bytes. The key kept
     * is the last one handed over, which is what the creator seals with from then on. It is not
     * backed up when the catch-up removed the user again, see [drain].
     *
     * The key is in the payload of the Welcome, see [welcomeHandoverOf], with the keys to the
     * members' profiles, which are taken with those the catch-up read, see [takeProfileKeys], once
     * the group is saved, and before anything is acknowledged, the creator's own as of when the
     * server stored the Welcome. A payload that does not read as one hands over no key. The
     * Welcome it came in is to this group and no other, the inspection having refused any other,
     * and only whoever wrote that Welcome can have put it there.
     *
     * Saved with the members the group holds once caught up, the user among them, as the Welcome's
     * tree and every Commit after it name them, rather than those the server lists, see
     * [joinGroupOf], and whoever wrote the Welcome as its creator rather than the owner the server
     * lists, see [GroupEntity.ownerUuid]. Joined, caught up and saved under [withGroupJoinLock], so
     * that no direct message is read in the group while the library holds it and the database does
     * not name it yet, see [message.DirectMessageSync], and refused outside it: refusing asks the
     * server.
     *
     * Not cancellable, which the caller sees to.
     */
    private suspend fun enter(
        group: GroupDto,
        client: MlsClient,
        welcome: WaitingWelcome,
        messages: List<GroupMessageDto>,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        refuse: suspend () -> GroupSyncError?,
    ): GroupSyncError? {
        val inspection = welcome.inspection
        val after = messages.drop(welcome.index + 1)
        val creatorUuid = inspection.committerUserUuid
        val sealedFields = group.sealedFields()
        val handover = inspection.welcomePayload?.let { welcomeHandoverOf(payload = it) }
        val welcomeKey = handover?.groupKey

        if (welcomeKey?.let { sealedFields.unseal(groupKey = it, groupUuid = group.uuid) } == null && after.isEmpty()) {
            return refuse()
        }

        val entry = try {
            withGroupJoinLock<Entry> {
                val mlsGroupId = client.joinGroupOf(
                    welcome = welcome.message,
                    groupUuid = group.uuid,
                    expectedMembers = inspection.memberUuids,
                )

                val caughtUp = readAll(
                    client = client,
                    mlsGroupId = mlsGroupId,
                    creatorUuid = creatorUuid,
                    memberUuids = inspection.memberUuids + selfUuid,
                    messages = after,
                )

                val groupKeys = listOfNotNull(welcomeKey) + caughtUp.groupKeys

                // Newest first: the server re-sealed them under each key the creator handed over.
                val fields = groupKeys.asReversed().firstNotNullOfOrNull { groupKey ->
                    sealedFields.unseal(groupKey = groupKey, groupUuid = group.uuid)
                }

                if (fields == null) {
                    client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

                    return@withGroupJoinLock Entry.Refused
                }

                val groupKey = groupKeys.last()

                val kept = keep(
                    group = group,
                    fields = fields,
                    creatorUuid = creatorUuid,
                    memberUuids = caughtUp.memberUuids ?: (inspection.memberUuids + selfUuid),
                    mlsGroupId = mlsGroupId,
                    groupKey = groupKey,
                    isBackedUp = false,
                    isLeft = caughtUp.isRemoved,
                )

                // Not kept, the Welcome is spent all the same, so the group goes, and the next sync
                // finds the Welcome refused as one already joined. Removed again, the user has no
                // use for its state either, see [drain].
                if (!kept || caughtUp.isRemoved) client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

                if (kept) Entry.Kept(fields = fields, groupKey = groupKey, caughtUp = caughtUp) else Entry.Lost
            }
        } catch (exception: MlsException) {
            // Past the inspection, the join refuses for good only a group too large to store, or one
            // seating a member pinned to another identity meanwhile, by a contact sync running
            // alongside say, which neither lock keeps out. Nothing can have taken the group's id: no
            // channel or contact request can be given it, and every Welcome to this group is joined
            // here, under [withGroupsLock].
            return if (exception.isRetryable()) GroupSyncError.Storage else refuse()
        }

        val kept = when (entry) {
            is Entry.Kept -> entry
            Entry.Refused -> return refuse()
            Entry.Lost -> return GroupSyncError.Storage
        }

        val creatorFullName = fullName(creatorUuid)

        // What the pass says of the group is where it leaves it, see [GroupSyncPass.removed].
        removed.removeAll { it.groupUuid == group.uuid }

        if (kept.caughtUp.isRemoved) {
            removed += GroupRemoval(
                groupUuid = group.uuid,
                title = kept.fields.title,
                removerUuid = creatorUuid,
                removerFullName = creatorFullName,
            )
        } else {
            joined += GroupArrival(
                groupUuid = group.uuid,
                title = kept.fields.title,
                adderUuid = creatorUuid,
                adderFullName = creatorFullName,
            )
        }

        var failure = kept.caughtUp.failure

        if (failure != null) behind += group.uuid

        // Not from a group the user was removed from again, as [drain] does not. The Welcome's
        // first, as handed over first, at the time the server stored it, which it lists the
        // Welcome with as it does any message.
        if (!kept.caughtUp.isRemoved) {
            val welcomeHandover = handover?.let {
                ProfileKeyHandover.Relayed(profileKeys = it.profileKeys, sentAt = messages[welcome.index].sentAt())
            }

            takeProfileKeys(
                groupUuid = group.uuid,
                creatorUuid = creatorUuid,
                selfUuid = selfUuid,
                handedOver = listOfNotNull(welcomeHandover) + kept.caughtUp.profileKeys,
                memberUuids = kept.caughtUp.memberUuidsSeen,
            )
        }

        // One the vault would not take now, the next sync backs up. Not for a group the user was
        // removed from again, as [drain] does not: a vault write, which throws once the device
        // locks, after the removal is recorded and before it is reported or acknowledged.
        if (!kept.caughtUp.isRemoved) {
            backUp(group.uuid, kept.groupKey).onError { failure = failure ?: GroupSyncError.Vault(it) }
        }

        // Whatever was left waiting behind a message that could not be read yet stays, for the next
        // sync to read. One whose acknowledgement does not go through stays listed with a group
        // this device now holds, which holds nothing back: the key package the Welcome was sealed
        // to is spent, and the next sync acknowledges it as a message that does not read.
        acknowledge(group.uuid, messages.take(welcome.index + 1).map { it.uuid } + kept.caughtUp.handled)
            .onError { failure = failure ?: GroupSyncError.Network(it) }

        return failure
    }

    /**
     * Whether [userUuid] may add the user to a group: one of their contacts, unblocked, whose
     * identity this device pinned.
     *
     * The pin is what the inspection checked them against, the members of a group being joined
     * without pinning anybody, see [joinGroupOf]: for a contact this device never reached over MLS,
     * it would check them against nothing, and they would be whoever the server built the Welcome
     * as. A group the user creates asks the same of its invitees, see [GroupCreator].
     *
     * Throws [MlsException] when the pin could not be read.
     */
    private suspend fun mayAdd(client: MlsClient, userUuid: String): Boolean =
        isUnblockedContact(userUuid) && client.pinnedSignatureKeyFor(peerUuid = userUuid) != null

    /**
     * Declines the group [groupUuid], see [DeclinedGroupEntity]: recorded as declined, which makes
     * it a group to leave, and [leaveDeclined] leaves it later in the same pass, unless the server
     * lists the user as its owner, which keeps it declined and never left. Nothing waiting there is
     * acknowledged: the leave drops all of it, the Welcome among it, and whatever comes after it
     * until then. Recorded first, before anything that can throw, so that a device locking, or a
     * server not answering, leaves the leave owed rather than the group decided on again.
     *
     * Never fails, the leave being what can, see [leaveDeclined]: it returns no failure all the
     * same so as to stand where [passOver] does, as what [join] returns and [enter] refuses with.
     */
    private suspend fun decline(groupUuid: String): GroupSyncError? {
        recordDeclined(groupUuid)

        return null
    }

    /**
     * Refuses the way back into the group [groupUuid] the user was removed from, or brought back
     * after a log-out, every message waiting there, [messages], acknowledged, and leaves the group
     * on this device as it is, if at all, see [restore]. Not declined, see [decline], which would
     * leave the group, and refuse the Welcome of its creator adding the user back some later day.
     */
    private suspend fun passOver(groupUuid: String, messages: List<GroupMessageDto>): GroupSyncError? {
        acknowledge(groupUuid, messages.map { it.uuid }).onError { return GroupSyncError.Network(it) }

        return null
    }

    /**
     * Leaves on the server every group this device declined, see [DeclinedGroupEntity], forgetting
     * it was declined once the server took the leave, or said the user was never in the group, and
     * returns what kept a leave from going through: the first one the server did not answer stops
     * them all, the next faring no better, and books them all again, see
     * [UNANSWERED_LEAVE_RESEND_SECONDS].
     *
     * Not a group the server lists the user as the owner of, of those in [listed]: it refuses its
     * owner, who would leave the group with nobody able to change it, and the client cannot tell
     * that refusal from no answer. That is a group the user created, declined after a log-out
     * because its key was never backed up, or one handed to them as its owner deleted their
     * account: past saving either way, only its creator's device changing a group. It stays
     * declined, neither asked about nor left.
     *
     * Forgotten, a group is decided on afresh should the server list it again: its creator added
     * the user back, whose new Welcome is the first thing waiting then, the leave having dropped
     * everything before it, or the proxy answered the `404` for the server, see
     * [GroupLeaveAnswer.NotMember], and the group is declined and left again. A leave the server
     * took whose answer was lost is sent again, and leaves again a group whose creator added the
     * user back in between: that takes their creator syncing and adding them back before the leave
     * goes out again, which is accepted.
     *
     * Nothing is forgotten before the server answered, so a device locking as this runs, see
     * [GroupSyncError.DeviceLocked], leaves every group not left yet owed for the next pass.
     */
    private suspend fun leaveDeclined(listed: List<GroupDto>, selfUuid: String): GroupSyncError? {
        val owners = listed.associate { it.uuid to it.ownerUuid }

        for (groupUuid in declinedGroups()) {
            if (owners[groupUuid] == selfUuid) continue

            when (val answer = leave(groupUuid)) {
                GroupLeaveAnswer.Left, GroupLeaveAnswer.NotMember -> forgetDeclined(groupUuid)

                is GroupLeaveAnswer.Failed -> {
                    resendScheduler.resendAfter(seconds = UNANSWERED_LEAVE_RESEND_SECONDS)

                    return GroupSyncError.Network(answer.error)
                }
            }
        }

        return null
    }

    /**
     * Saves [group] with its [fields], [creatorUuid] as its creator, and its members, [memberUuids],
     * the creator their admin, in place of whatever this device held of it, and says whether it was
     * saved.
     */
    private suspend fun keep(
        group: GroupDto,
        fields: GroupFields,
        creatorUuid: String,
        memberUuids: List<String>,
        mlsGroupId: ByteArray?,
        groupKey: ByteArray,
        isBackedUp: Boolean,
        isLeft: Boolean,
    ): Boolean {
        val entity = GroupEntity(
            uuid = group.uuid,
            title = fields.title,
            description = fields.description,
            currency = fields.currency,
            ownerUuid = creatorUuid,
            mlsGroupId = mlsGroupId,
            groupKey = groupKey,
            isBackedUp = isBackedUp,
            isLeft = isLeft,
            // Owed, as every group the user joins is, see [takeProfileKeys].
            handedProfileKeyHash = null,
        )

        return try {
            save(entity, membersOf(groupUuid = group.uuid, memberUuids = memberUuids, creatorUuid = creatorUuid))

            true
        } catch (_: Exception) {
            false
        }
    }
}

/** What one message waiting in a group came to, see [GroupSync.read]. */
private sealed interface GroupMessageReading {

    /** A Commit from the creator, applied: the group now holds [memberUuids], the user among them. */
    class Committed(val memberUuids: List<String>) : GroupMessageReading

    /** A Commit from the creator removing the user, after which the group cannot be read in. */
    data object Removed : GroupMessageReading

    /** The creator handing over [groupKey], the group's new key, see [groupKeyOf]. */
    class Rekeyed(val groupKey: ByteArray) : GroupMessageReading

    /** Keys to the members' profiles handed over, see [ProfileKeyHandover]. */
    class HandedProfileKeys(val handover: ProfileKeyHandover) : GroupMessageReading

    /** Nothing for the user: read and of no use to them, or refused for good. Acknowledged either way. */
    data object Ignored : GroupMessageReading

    /** Not read yet, and to be read by a later sync, everything after it waiting behind it: [failure] says why. */
    class Waiting(val failure: GroupSyncError) : GroupMessageReading
}

/** Keys to the profiles of the members of a group, handed over in it, see [GroupSync.takeProfileKeys]. */
private sealed interface ProfileKeyHandover {

    /**
     * When the server stored the message, or the Welcome, handing them over, in milliseconds since
     * the epoch, see [GroupMessageDto.sentAt]: what ranks two keys from the same owner, see
     * [MemberProfileRepository.takeOwnerKey].
     */
    val sentAt: Long

    /**
     * The creator's word for the key to the profile of every member, [profileKeys], by user UUID:
     * the map his Welcome carries, see [welcomeHandoverOf], or the one he sends the members already
     * in as he adds somebody, see [profileKeysOf].
     */
    class Relayed(val profileKeys: Map<String, ByteArray>, override val sentAt: Long) : ProfileKeyHandover

    /** [ownerUuid] handing over [profileKey], the key to their own profile, see [ownProfileKeyOf]. */
    class Own(val ownerUuid: String, val profileKey: ByteArray, override val sentAt: Long) : ProfileKeyHandover
}

/** A Welcome waiting in a group, at [index] among its messages, decoded, and what [inspection] read in it. */
private class WaitingWelcome(val index: Int, val message: ByteArray, val inspection: WelcomeInspection)

/** What reading the messages waiting in a group came to, see [GroupSync.readAll]. */
private class GroupReading(
    /** Every message read, or refused for good, in the order read: those to acknowledge. */
    val handled: List<String>,

    /** Who the group holds after the last Commit read, the user among them, or null when none was. */
    val memberUuids: List<String>?,

    /** Everybody the group held at one point or another of what was read, the user among them. */
    val memberUuidsSeen: Set<String>,

    /** Every key the creator handed over, in the order handed over. */
    val groupKeys: List<ByteArray>,

    /** Every key to a member's profile handed over, in the order handed over, see [ProfileKeyHandover]. */
    val profileKeys: List<ProfileKeyHandover>,

    /** Whether a Commit removed the user, the last message read then. */
    val isRemoved: Boolean,

    /** What kept the messages after the last one read from being read, see [GroupMessageReading.Waiting]. */
    val failure: GroupSyncError?,
)

/** What joining a group came to, see [GroupSync.enter]. */
private sealed interface Entry {

    /** Joined and saved, with the [fields] it opened to, the [groupKey] kept and what [caughtUp] read. */
    class Kept(val fields: GroupFields, val groupKey: ByteArray, val caughtUp: GroupReading) : Entry

    /** Joined, and deleted again, the fields opening under no key its creator handed over. */
    data object Refused : Entry

    /** Joined, and deleted again, the group not saved. */
    data object Lost : Entry
}

/**
 * Runs [block] and says whether it ran to its end, rather than stopping as the device locked: what
 * this device stores is read with keys a locked device cannot use, and reading it throws an
 * [IllegalStateException] then, see [GroupSyncError.DeviceLocked].
 */
private inline fun runsUnlocked(block: () -> Unit): Boolean =
    try {
        block()

        true
    } catch (exception: CancellationException) {
        // The caller going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        false
    }

/** The members of the group [groupUuid], [memberUuids], [creatorUuid] their admin. */
private fun membersOf(groupUuid: String, memberUuids: List<String>, creatorUuid: String) =
    memberUuids.map { GroupMemberEntity(userUuid = it, groupUuid = groupUuid, isAdmin = it == creatorUuid) }

/** The fields of this group as the server holds them, sealed. */
private fun GroupDto.sealedFields() = SealedGroupFields(title = title, description = description, currency = currency)

/**
 * When the server stored this message, in milliseconds since the epoch: the one clock every message
 * of every group is stamped by, which it lists them in the order of. The API writes it for each
 * message it lists, in UTC, to the millisecond, see [GroupMessageDto.createdAt].
 */
@OptIn(ExperimentalTime::class)
private fun GroupMessageDto.sentAt(): Long = Instant.parse(createdAt).toEpochMilliseconds()
