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
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.repository.ContactRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import message.ResendScheduler
import mls.EpochKey
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.commitEpochOf
import mls.deleteGroupOf
import mls.deleteGroupQuietly
import mls.epochKeyIn
import mls.inspectGroupWelcome
import mls.isRetryable
import mls.joinGroupOf
import mls.pinnedSignatureKeyFor
import mls.processGroupMessageIn
import mls.rosterOf
import push.WakeList
import uniffi.lib_duckcount.CommitPolicy
import uniffi.lib_duckcount.MemberInfo
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.WelcomeInspection
import util.EmptyResult
import util.NetworkError
import util.Result
import util.nowEpochMilliseconds
import util.onError
import vault.VaultError
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How soon a leave the server was not heard to take is sent again, see [GroupSync.leaveDeclined]
 * and [GroupLeaver]: as soon as a change to a group's members is, see [GroupMembershipSender].
 * Nothing else may bring the sync back for it soon: a group is mostly declined for a creator who
 * is no contact of the user's, whose pushes their phone refuses, see [push.PushSignal].
 */
internal const val UNANSWERED_LEAVE_RESEND_SECONDS = 60L

/**
 * How long a member who is not a group's creator waits, from when a member departed from it, see
 * [GroupDto.departed], before their device removes them, see [GroupSync.dropDeparted]: the
 * creator's device removes them at once, every member being woken by the leave, see
 * [push.PushSignal.Kind.GROUP_MEMBER_LEFT], so that the creator, when online, is the one who does,
 * and two members committing the same removal at once stay rare. Should the creator be offline,
 * the departed member is cut off as soon as one remaining member is online once it is over.
 */
internal val DEPARTED_GRACE: Duration = 10.minutes

/** A group the user was added to, or added back to, and joined in a [GroupSync] pass, for them to be told about. */
data class GroupArrival(
    val groupUuid: String,
    val title: String,

    /** Who added the user: whoever wrote the Welcome, one of the user's contacts. */
    val adderUuid: String,

    /** Null while this device has yet to read their name. */
    val adderFullName: String?,
)

/** A group a Commit removed the user from, as a [GroupSync] pass read, for them to be told about. */
data class GroupRemoval(
    val groupUuid: String,
    val title: String,

    /**
     * Who removed the user: whoever made the Commit doing so, as MLS says, the group's creator for
     * any removal but that of a member who departed, which any member may make, see
     * [GroupMembershipChanger.removeDepartedUnderGroupsLock]. Its creator for a group brought back
     * after a log-out, which holds no MLS state to read the Commit with, see [GroupEntity.ownerUuid].
     */
    val removerUuid: String,

    /** Null while this device has yet to read their name. */
    val removerFullName: String?,
)

/** A group its owner deleted for everybody, as a [GroupSync] pass found, for the user to be told about. */
data class GroupDeletion(
    val groupUuid: String,
    val title: String,

    /** Who deleted it, or their account: its creator, see [GroupEntity.ownerUuid]. */
    val deleterUuid: String,

    /**
     * Null while this device has yet to read their name. Read even if they are hidden: an owner
     * deleting their account tells their contacts first, which hides them before the sync finds the
     * group gone.
     */
    val deleterFullName: String?,
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
     * The groups found deleted by their owner, whatever else the pass came to, but for one the user
     * owns: they know, having asked for it.
     */
    val deleted: List<GroupDeletion>,

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
 * yet, and the members', who join it from its Welcome and follow what its Commits change of it.
 *
 * In this order, under [withGroupsLock] as a creation and a change to a group's members are, since
 * they all read and write the same:
 * 1. Every creation the server has not been heard to take is sent again, see
 *    [GroupCreationSender.resendPending], and so is every Commit of the user's, see
 *    [GroupMembershipSender.resendPending]. One pending in a group the server no longer lists, the
 *    user removed from it since, is settled from what waits there instead, see
 *    [GroupMembershipSender.settleUnlisted].
 * 2. Every group the user is still in that the server no longer lists, and whose messages it says
 *    are not there, is one its owner deleted, see [takeDeletions]: the group stays read-only, as
 *    after a removal, and takes no further part in the pass.
 * 3. What waits in every group this device holds the MLS state of and the user is still in is
 *    read, see [drain], whether or not the server still lists the group: the server lists a group
 *    to its current members alone, and the Commit removing the user is how this device learns it.
 *    Every Commit read takes the group to an epoch whose key MLS derives then and only then, which
 *    is kept as its generation, see [GroupKeyEntity]. A group this device finds itself behind the
 *    server in, with nothing left to read, waits for its creator, whose device replaces the Commit
 *    it refused, see [standing].
 * 4. Every group brought back after a log-out that the server lists, with somebody else in it, is
 *    rejoined, see [GroupRejoiner]: from a GroupInfo of the epoch the server is at that a member
 *    this device pinned signed, which the other members' devices publish once the user's asked for
 *    one, as it brought the group back, see [restore]. Rejoined, it is held from there on as every
 *    group this device holds the MLS state of is, by the rest of this very pass.
 * 5. Every group the server lists has whoever it lists as departed removed from it, see
 *    [dropDeparted]: those who left it, on purpose, see [GroupLeaver], or as a device declining it
 *    does, and accounts deleted since. By its creator's device at once, and by any other member's
 *    once [DEPARTED_GRACE] has gone by, a sync booked for then.
 * 6. Every group brought back after a log-out and not rejoined yet that the server lists takes the
 *    members it lists of it, see [refresh], and every one it no longer lists is one the user was
 *    removed from, see [leaveUnlisted]: there is no MLS state to read the Commits doing so with.
 *    Every group this device holds that the user is still in shows the version of its information
 *    the server lists, when it is newer than the one shown and its creator signed it, see
 *    [takeEdits].
 * 7. Every group the server lists that this device neither holds nor declined is taken in, see
 *    [takeIn]: brought back from the user's copy of its key, which the server lists with it, see
 *    [GroupKeyCopies.open], after a log-out took it off this device, or else joined from the
 *    Welcome waiting there, or declined. One with neither is declined too, nothing being left that
 *    could ever open it here. One whose information the key its copy holds, and the keys the chain
 *    leads back to from it, see [GroupKeyChain], do not open, its creator having edited it in a
 *    later epoch, or open to a version the creator the server lists did not sign, is neither
 *    brought back nor declined: it is joined only from the Welcome of that creator adding the user
 *    back, see [restore] and [rejoin], what waits there until then acknowledged unread, and every
 *    sync tries it again. Every group the user was removed from, or left once its leave is no
 *    longer owed, that the server lists again is taken in as well, joined again from the Welcome
 *    of its creator adding them back, see [rejoin], what waits before it acknowledged with it, and
 *    so is every group brought back after a log-out and not rejoined yet, which that gives MLS
 *    state again too, what waits there with no such Welcome acknowledged unread. Every one still
 *    not rejoined then, with nothing to rejoin from, asks for a GroupInfo again, see [askAgain].
 * 8. Every leave owed is sent, see [leaveDeclined]: that of every group this device declined,
 *    those this pass declined included, and of every group the user left, see [GroupLeaver].
 * 9. Once every group is caught up, the key to the user's own profile is posted into each owed
 *    it: each they joined, or rejoined, or whose creator seated somebody, since they last posted it
 *    there, see [takeProfileKeys], and each once their key rotates, see [GroupProfileKeyDelivery].
 * 10. Last, the user's copy of the key of every group the server lists is put up wherever the
 *    listing shows none, or this device holds a key whose copy has not gone up, see
 *    [GroupKeyCopies.putUp]: each group this pass joined, and each a Commit read took to a new
 *    epoch, among them, the copy of the newest generation this device holds. And every generation
 *    a Commit read led to has the links the server lists there checked, see [checkKeyLinks]. Nothing
 *    else in the pass waits on either, and neither runs between reading a group's messages and
 *    acknowledging them, see [drain] and [enter]: sealing a copy reads the key ring, which throws
 *    once the device locks. Nor on the GroupInfo of every group whose listing says another member
 *    asked for one, which goes up then too, see [GroupRejoiner.publishUnderGroupsLock].
 * 11. Who may wake the user is rebuilt with the members the groups hold now, see [WakeList]: those
 *    of a group the user was removed from no longer do, and those of a group they joined do, with
 *    an expense of it, a change to it and a leave from it.
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
 * Once joined, a group changes by its creator's Commits, see [GroupEntity.ownerUuid], which are
 * taken whatever they change, and by any member's removal of those who departed, taken only when it
 * removes exactly the members the server checked had departed as it stored it, and nothing else,
 * see [read]: every honest member's device decides alike about every Commit, so that one they all
 * refuse is one the creator's device replaces, see [standing]. The versions of its information are
 * taken from its creator alone, see [takeEdits]. Every key an epoch gives is kept with its
 * generation, see [GroupKeyEntity], none in place of the one before it: what was sealed under that
 * one stays sealed under it. So is the signature key every member holds in the group's tree, as
 * the Welcome and each Commit read leave it, see [GroupMemberSignatureKeyEntity], kept once they
 * leave. The keys to the members' profiles are the one thing anybody else hands over: each member
 * their own, which beats the one the creator relayed for them, and any of theirs that came in an
 * earlier message, see [takeProfileKeys].
 *
 * Of a group this device holds the MLS state of, the one thing the server's word decides of its
 * members is who is no longer one: this device removes whoever the server lists as departed, see
 * [dropDeparted], and the key of the epoch the removal leads to is one they never derive. A server
 * lying about it can only have members removed, which it can do in effect already by no longer
 * delivering to them. It cannot have anybody added to such a group, nor any member take a Commit
 * the server only claims removes the departed: the Commit's own proposals say whom it removes. A
 * group brought back after a log-out holds no tree to tell its members, so it takes the members and
 * the creator the server lists, additions included, see [restore] and [refresh], until it rejoins
 * the group, see [GroupRejoiner], or the Welcome of that creator adding the user back gives it MLS
 * state again, see [rejoin]. The tree a rejoin takes is that of a GroupInfo signed by a member this
 * device pinned, or by the user, holding the members the server lists and those departed, see
 * [mls.rejoinGroupOf]: the server can have it rejoin the group as it stands, and no other. And the
 * creator it lists decides whose Welcome a group is joined from when the first message waiting
 * there is no way in at all, see [join]: a server lying about it can have the user join from the
 * Welcome of somebody else who may add them to a group, which it can do anyway by holding back
 * whatever waits before that Welcome.
 *
 * Every step is attempted even when one fails, and the first failure is reported, with the groups
 * joined and left either way, see [GroupSyncPass]: what was joined or left is told about once, by
 * the pass that read it. Whatever did not get done is picked up by the next sync. The device
 * locking stops the pass where it is instead, nothing this device stores being readable then, and
 * the groups joined and left until that point are reported all the same, see
 * [GroupSyncError.DeviceLocked].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [resendPending] is [GroupCreationSender.resendPending],
 * [resendMembershipChanges] and [settleUnlisted] are [GroupMembershipSender.resendPending] and
 * [GroupMembershipSender.settleUnlisted], [openKeyCopies] and
 * [putUpKeyCopies] are [GroupKeyCopies.open] and [GroupKeyCopies.putUp], [restoreContacts] is
 * [ContactRepository.restore], [listGroups], [listMessages], [acknowledge], [leave] and [keyLinks]
 * are [GroupApi.getGroups], [GroupApi.getGroupMessages], [GroupApi.acknowledgeGroupMessages],
 * [GroupApi.leave] and [GroupApi.keyLinks], [storedGroups], [declinedGroups], [save],
 * [memberUuidsOf], [replaceMembers], [recordSignatureKeys], [takeKeys], [updateInformation],
 * [markLeft], [recordDeclined], [forgetDeclined] and [setAwaitingOwner] are [GroupDao.getAll],
 * [GroupDao.getDeclinedUuids], [GroupDao.upsertWithMembers], [GroupDao.getMemberUuids],
 * [GroupDao.replaceMembers], [GroupDao.upsertSignatureKeys], [GroupDao.takeDerivedKeys],
 * [GroupDao.updateInformation], [GroupDao.markLeft], [GroupDao.insertDeclined],
 * [GroupDao.deleteDeclined] and [GroupDao.setAwaitingOwner], [removeDeparted], [replaceRefused]
 * and [remakeOutdated] are [GroupMembershipChanger.removeDepartedUnderGroupsLock],
 * [GroupMembershipChanger.replaceUnderGroupsLock] and
 * [GroupMembershipChanger.remakeOutdatedUnderGroupsLock], [keyOf] is [GroupKeyChain.keyOf],
 * [checkKeyLinks] is [GroupKeyLinks.check], [requestRejoin] is [GroupApi.requestRejoin],
 * [recordMissingSignatureKeys] is [GroupRejoiner.recordMissingSignatureKeys],
 * [rejoinRestored] and [publishGroupInfos] are [GroupRejoiner.rejoinUnderGroupsLock] and
 * [GroupRejoiner.publishUnderGroupsLock], [isUnblockedContact] is [ContactDao.isUnblocked],
 * [client] is [MlsIdentityRepository.client], [fullName] and [fullNameEvenIfHidden] are
 * [UserDao.getFullName] and [UserDao.getFullNameEvenIfHidden],
 * [hasPendingChange] is [GroupDao.hasChangeOnServer], [hasPendingCreation] is
 * [GroupDao.hasPendingCreation], [markDeleted] is [GroupDao.markDeleted], [isBlocked] is
 * [IdentityConflictDao.isBlocked], [setHandedProfileKey] is [GroupDao.setHandedProfileKeyHash],
 * [takeOwnerKey], [fillGap], [restoreMemberProfiles], [readMemberNames] and [backUpMemberProfiles]
 * are [MemberProfileRepository.takeOwnerKey], [MemberProfileRepository.fillGap],
 * [MemberProfileRepository.restore], [MemberProfileRepository.readNames] and
 * [MemberProfileRepository.backUp], [handOutProfileKey] is [GroupProfileKeyDelivery.handOut],
 * [rebuildWakeList] is [WakeList.rebuild] and [now] is the time in milliseconds since the epoch, or
 * what a test stands in for any of them, as [resendScheduler] is for the scheduler bound on this
 * platform.
 */
class GroupSync internal constructor(
    private val userUuid: () -> String?,
    private val resendPending: suspend () -> EmptyResult<NetworkError>,
    private val resendMembershipChanges: suspend () -> EmptyResult<GroupSyncError>,
    private val settleUnlisted: suspend (
        listedUuids: Set<String>,
        firstCommitEpochIn: suspend (groupUuid: String, fromEpoch: Long) -> Result<Long?, NetworkError>,
    ) -> EmptyResult<GroupSyncError>,
    private val openKeyCopies: suspend (groups: List<GroupDto>) -> Result<Map<String, GroupKeyGeneration>, VaultError>,
    private val putUpKeyCopies: suspend (listed: List<GroupDto>) -> EmptyResult<GroupSyncError>,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val storedGroups: suspend () -> List<GroupEntity>,
    private val declinedGroups: suspend () -> List<String>,
    private val listMessages: suspend (groupUuid: String) -> Result<ListGroupMessagesResponse, NetworkError>,
    private val acknowledge: suspend (groupUuid: String, messageUuids: List<String>) -> EmptyResult<NetworkError>,
    private val leave: suspend (groupUuid: String) -> GroupLeaveAnswer,
    private val keyLinks: suspend (groupUuid: String) -> Result<List<GroupKeyLinkDto>, NetworkError>,
    private val isUnblockedContact: suspend (userUuid: String) -> Boolean,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val save: suspend (
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        keys: List<GroupKeyEntity>,
        signatureKeys: List<GroupMemberSignatureKeyEntity>,
    ) -> Unit,
    private val memberUuidsOf: suspend (groupUuid: String) -> List<String>,
    private val replaceMembers: suspend (groupUuid: String, members: List<GroupMemberEntity>) -> Unit,
    private val recordSignatureKeys: suspend (signatureKeys: List<GroupMemberSignatureKeyEntity>) -> Unit,
    private val takeKeys: suspend (groupUuid: String, keys: List<GroupKeyEntity>) -> Unit,
    private val updateInformation: suspend (groupUuid: String, version: GroupVersion) -> Unit,
    private val markLeft: suspend (groupUuid: String) -> Unit,
    private val recordDeclined: suspend (groupUuid: String) -> Unit,
    private val forgetDeclined: suspend (groupUuid: String) -> Unit,
    private val setAwaitingOwner: suspend (groupUuid: String, isAwaitingOwner: Boolean) -> Unit,
    private val removeDeparted: suspend (
        groupUuid: String,
        userUuids: Set<String>,
    ) -> Result<MembershipChangeOutcome, GroupMembershipError>,
    private val replaceRefused: suspend (
        groupUuid: String,
        userUuids: Set<String>,
    ) -> Result<MembershipChangeOutcome, GroupMembershipError>,
    private val remakeOutdated: suspend (groupUuid: String) -> Result<MembershipChangeOutcome?, GroupMembershipError>,
    private val keyOf: suspend (
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ) -> Result<ByteArray, GroupKeyChainError>,
    private val checkKeyLinks: suspend () -> EmptyResult<GroupSyncError>,
    private val requestRejoin: suspend (groupUuid: String) -> EmptyResult<NetworkError>,
    private val rejoinRestored: suspend (listings: Map<String, GroupDto>, selfUuid: String) -> GroupRejoinPass,
    private val publishGroupInfos: suspend (listed: List<GroupDto>, behind: Set<String>) -> EmptyResult<GroupSyncError>,
    private val recordMissingSignatureKeys: suspend () -> Unit,
    private val fullName: suspend (userUuid: String) -> String?,
    private val fullNameEvenIfHidden: suspend (userUuid: String) -> String?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val hasPendingCreation: suspend (groupUuid: String) -> Boolean,
    private val markDeleted: suspend (groupUuid: String) -> Unit,
    private val isBlocked: suspend (userUuid: String) -> Boolean,
    private val setHandedProfileKey: suspend (groupUuid: String, handedProfileKeyHash: String?) -> Unit,
    private val takeOwnerKey: suspend (userUuid: String, profileEncryptionKey: ByteArray, sentAt: Long) -> Unit,
    private val fillGap: suspend (userUuid: String, profileEncryptionKey: ByteArray) -> Unit,
    private val restoreMemberProfiles: suspend () -> EmptyResult<GroupSyncError>,
    private val readMemberNames: suspend () -> EmptyResult<GroupSyncError>,
    private val backUpMemberProfiles: suspend () -> EmptyResult<GroupSyncError>,
    private val handOutProfileKey: suspend (behind: Set<String>) -> EmptyResult<GroupSyncError>,
    private val rebuildWakeList: suspend () -> Unit,
    private val now: () -> Long,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupCreationSender: GroupCreationSender,
        groupMembershipSender: GroupMembershipSender,
        groupMembershipChanger: GroupMembershipChanger,
        groupKeyCopies: GroupKeyCopies,
        groupKeyChain: GroupKeyChain,
        groupKeyLinks: GroupKeyLinks,
        groupRejoiner: GroupRejoiner,
        groupProfileKeyDelivery: GroupProfileKeyDelivery,
        memberProfileRepository: MemberProfileRepository,
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        userDao: UserDao,
        wakeList: WakeList,
        resendScheduler: ResendScheduler,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        resendPending = groupCreationSender::resendPending,
        resendMembershipChanges = groupMembershipSender::resendPending,
        settleUnlisted = groupMembershipSender::settleUnlisted,
        openKeyCopies = groupKeyCopies::open,
        putUpKeyCopies = groupKeyCopies::putUp,
        restoreContacts = contactRepository::restore,
        listGroups = groupApi::getGroups,
        storedGroups = groupDao::getAll,
        declinedGroups = groupDao::getDeclinedUuids,
        listMessages = groupApi::getGroupMessages,
        acknowledge = groupApi::acknowledgeGroupMessages,
        leave = groupApi::leave,
        keyLinks = groupApi::keyLinks,
        isUnblockedContact = contactDao::isUnblocked,
        client = mlsIdentityRepository::client,
        save = groupDao::upsertWithMembers,
        memberUuidsOf = groupDao::getMemberUuids,
        replaceMembers = groupDao::replaceMembers,
        recordSignatureKeys = groupDao::upsertSignatureKeys,
        takeKeys = groupDao::takeDerivedKeys,
        updateInformation = { groupUuid, version ->
            groupDao.updateInformation(
                uuid = groupUuid,
                title = version.fields.title,
                description = version.fields.description,
                infoRevision = version.revision,
            )
        },
        markLeft = groupDao::markLeft,
        recordDeclined = { groupUuid -> groupDao.insertDeclined(DeclinedGroupEntity(groupUuid = groupUuid)) },
        forgetDeclined = groupDao::deleteDeclined,
        setAwaitingOwner = { groupUuid, isAwaitingOwner ->
            groupDao.setAwaitingOwner(uuid = groupUuid, isAwaitingOwner = isAwaitingOwner)
        },
        removeDeparted = groupMembershipChanger::removeDepartedUnderGroupsLock,
        replaceRefused = groupMembershipChanger::replaceUnderGroupsLock,
        remakeOutdated = groupMembershipChanger::remakeOutdatedUnderGroupsLock,
        keyOf = groupKeyChain::keyOf,
        checkKeyLinks = groupKeyLinks::check,
        requestRejoin = groupApi::requestRejoin,
        rejoinRestored = groupRejoiner::rejoinUnderGroupsLock,
        publishGroupInfos = groupRejoiner::publishUnderGroupsLock,
        recordMissingSignatureKeys = groupRejoiner::recordMissingSignatureKeys,
        fullName = userDao::getFullName,
        fullNameEvenIfHidden = userDao::getFullNameEvenIfHidden,
        hasPendingChange = groupDao::hasChangeOnServer,
        hasPendingCreation = groupDao::hasPendingCreation,
        markDeleted = groupDao::markDeleted,
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
        rebuildWakeList = wakeList::rebuild,
        now = ::nowEpochMilliseconds,
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
        val deleted = mutableListOf<GroupDeletion>()
        var failure: GroupSyncError? = null

        val isRunThrough = runsUnlocked {
            withGroupsLock {
                resendPending().onError { failure = failure ?: GroupSyncError.Network(it) }

                resendMembershipChanges().onError { failure = failure ?: it }

                bringInLine(joined = joined, removed = removed, deleted = deleted, contactsTakenIn = contactsTakenIn)
                    ?.let { failure = failure ?: it }

                // Whatever came of the rest, as the contacts' backup rebuilds it, see WakeList.
                rebuildWakeList()
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
            deleted = deleted.toList(),
            failure = if (isRunThrough) failure else GroupSyncError.DeviceLocked,
        )
    }

    /**
     * Whether no group the server lists for the user holds a Welcome this device has yet to take
     * in. One could need the secret of one of the user's key packages, see
     * [mls.KeyPackageRepository]. False when the server could not be asked.
     *
     * Only the groups this device neither holds nor declined, those the user was removed from or
     * left, which the server lists again once their creator added the user back, the Welcome doing
     * so waiting there, and those brought back after a log-out, which the creator removing the user
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
     * Reads what waits in the user's groups, rejoins those a log-out took the MLS state of, see
     * [GroupRejoiner], brings those the server lists in line with it, and takes in those it lists
     * that are new to this device, see [GroupSync], noting in [joined], [removed] and [deleted] the
     * groups the user was added to and removed from, and those found deleted, then leaves those this
     * device declined, posts the user's own profile key into the groups owed it, see
     * [GroupProfileKeyDelivery], puts up the copies of their keys the server is owed, see
     * [GroupKeyCopies.putUp], publishes the GroupInfos other members asked for, and returns the
     * first failure: a group that fails is left for the next sync, and the next group is seen to all
     * the same.
     */
    private suspend fun bringInLine(
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        deleted: MutableList<GroupDeletion>,
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

        val listings = listed.associateBy { it.uuid }

        // Before anything else reads them, none of it having anything left to read, the change
        // waiting in one of them included. Those it could not tell are told apart by the next pass.
        val untold = mutableSetOf<String>()

        var failure = takeDeletions(listings = listings, selfUuid = selfUuid, deleted = deleted, untold = untold)

        // Before the drain, which reads no group a change is pending in: one the server no longer
        // lists would be read never again, the Commit removing the user from it unread.
        (settleUnlisted(listings.keys, ::firstCommitEpochIn) as? Result.Error)?.error
            ?.let { failure = failure ?: it }

        drainAll(selfUuid = selfUuid, listings = listings, removed = removed, behind = behind)
            ?.let { failure = failure ?: it }

        // Once the drain is through, whose groups it is, and before the groups are read again: one
        // rejoined now is held from there on, and seen to as such by the rest of the pass, its
        // departed members removed and the user's key posted into it. One the server has not been
        // heard to take the rejoin of waits as any group a change of the user's is pending in does.
        val rejoins = rejoinRestored(listings, selfUuid)

        behind += rejoins.pending

        rejoins.failure?.let { failure = failure ?: it }

        // Read again: the drain took the user out of some, and the rejoin put others back in step.
        val stored = storedGroups().associateBy { it.uuid }

        // Once the device read what the members posted, in the epoch they posted it in: the
        // removal takes the group past it.
        dropDeparted(listings = listings, stored = stored, selfUuid = selfUuid, removed = removed, behind = behind)
            ?.let { failure = failure ?: it }

        refresh(listed = listed, stored = stored)

        takeEdits(listed = listed, stored = stored, selfUuid = selfUuid)?.let { failure = failure ?: it }

        leaveUnlisted(listed = listed, stored = stored, untold = untold, removed = removed)

        takeIn(
            listed = listed,
            stored = stored,
            selfUuid = selfUuid,
            joined = joined,
            removed = removed,
            behind = behind,
            contactsTakenIn = contactsTakenIn,
        )?.let { failure = failure ?: it }

        // Once the groups are taken in too: one its creator let the user in again by this very pass
        // has no use for a GroupInfo, and nothing would close the request but its lapsing.
        askAgain(groupUuids = rejoins.unanswered)?.let { failure = failure ?: it }

        // Once the groups are taken in, so that one declined just now is left by this very pass.
        leaveDeclined(listed = listed)?.let { failure = failure ?: it }

        // Once every group is caught up, so that each post goes out in the epoch its members are in.
        handOutProfileKey(behind).onError { failure = failure ?: it }

        // Last, once every group is taken in, and every key an epoch gave is taken: the copy of
        // each goes up in the very pass that took it. Nobody but the user waits on it, nor on the
        // links, whose check reads them all.
        putUpKeyCopies(listed).onError { failure = failure ?: it }

        checkKeyLinks().onError { failure = failure ?: it }

        // As for the links: a member who asked for a GroupInfo is the only one waiting on it.
        publishGroupInfos(listed, behind).onError { failure = failure ?: it }

        // Before the backup that carries them to the vault, once every group is taken in.
        recordMissingSignatureKeys()

        return failure
    }

    /**
     * Marks as deleted every group this device holds, that the user is still in, that the server no
     * longer lists in [listings], and that it says holds nothing for them either, noting it in
     * [deleted] unless the user owns it, having asked for it then, and returns the first failure,
     * the group it was about noted in [untold].
     *
     * The server lists a group to its current members alone, which leaves two ways for one to drop
     * out of the listing of somebody who is still in it here. A member the group's creator removed
     * still has a row in it, and `GET /groups/{group_uuid}/messages` serves them, the Commit doing
     * so among what waits there, see [drain]. A group its owner deleted, by itself or with their
     * account, has no rows left, and that route answers `404`, as it does anybody who was never in the group, see [GroupApi.delete]. So
     * does the proxy in front of the server as it is redeployed, see [GroupLeaveAnswer.NotMember],
     * which takes the server having just listed nothing of the group: a member it removed told it
     * was deleted, a removal it would have read otherwise. Nothing is lost that the Welcome of its
     * creator adding them back does not bring back, see [rejoin], which clears the mark.
     *
     * Not a group whose creation the server has not been heard to take, which it holds nothing of
     * yet, see [GroupCreationSender], nor one the user is no longer in: they were told of what took
     * them out, and whatever becomes of the group after that is none of their business.
     *
     * A server lying about it can only stop serving the group, which it can do anyway, and leave
     * every member what they held of it: nothing is taken off this device on its word, so that it
     * has no way to have a member lose what they held. It does cost a member the group's MLS state,
     * as stopping would cost them the group.
     *
     * The mark is recorded and noted before anything that could throw, as for a removal, see
     * [drain]: the next pass finds it deleted already, with nothing to tell. Any change to the
     * group's members waiting on the server goes with the mark, see [GroupDao.markDeleted], so it
     * is neither settled nor sent again.
     */
    private suspend fun takeDeletions(
        listings: Map<String, GroupDto>,
        selfUuid: String,
        deleted: MutableList<GroupDeletion>,
        untold: MutableSet<String>,
    ): GroupSyncError? {
        var failure: GroupSyncError? = null

        for (group in storedGroups().filterNot { it.isLeft }) {
            if (group.uuid in listings || hasPendingCreation(group.uuid)) continue

            when (val list = listMessages(group.uuid)) {
                is Result.Success -> continue

                is Result.Error -> if (list.error != NetworkError.NOT_FOUND) {
                    untold += group.uuid

                    failure = failure ?: GroupSyncError.Network(list.error)

                    continue
                }
            }

            // The row first, which keeps the group's id: whatever becomes of its state, a direct
            // message naming it is never read, see [GroupEntity.mlsGroupId].
            markDeleted(group.uuid)

            if (group.ownerUuid != selfUuid) {
                deleted += GroupDeletion(
                    groupUuid = group.uuid,
                    title = group.title,
                    deleterUuid = group.ownerUuid,
                    deleterFullName = fullNameEvenIfHidden(group.ownerUuid),
                )
            }

            group.mlsGroupId?.let { mlsGroupId ->
                (client() as? Result.Success)?.data?.deleteGroupQuietly(mlsGroupId = mlsGroupId)
            }
        }

        return failure
    }

    /**
     * Reads what waits in every group this device holds the MLS state of and the user is still in,
     * see [drain], each against what the server lists of it in [listings], by UUID, noting in
     * [removed] the groups the user was removed from, and in [behind] those it could not catch up
     * with, and returns the first failure: a group that fails is left for the next sync, and the
     * next group is drained all the same. What waits in one brought back after a log-out is left
     * to [rejoin], which looks there for the Welcome of its creator adding the user back, until it
     * is rejoined, see [GroupRejoiner], which drops all of it on the server.
     *
     * Nor is a group read while a Commit of the user's to it waits on the server, see
     * [GroupMembershipSender]: what its members post in the epoch the Commit takes it to, once the
     * server holds it, reads only once this device applied it too, and read before, it would be
     * refused for good, see [refusalOf]. It waits for a sync that settles the change, which the
     * sync tries before anything else, see [sync]. Any member may have one pending, the removal of
     * those who departed being any member's, see [dropDeparted]. It is noted in [behind] all the
     * same: the user's key posted into it now would go out in the epoch the change takes it from,
     * which those it seats cannot read. A change the user asked for whose Commit the server refused
     * for its epoch as it was sent again waits on nothing there: the group is read, and the change
     * made anew once it is, see [GroupMembershipChanger.remakeOutdatedUnderGroupsLock].
     */
    private suspend fun drainAll(
        selfUuid: String,
        listings: Map<String, GroupDto>,
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
                drain(
                    group = group,
                    mlsGroupId = mlsGroupId,
                    selfUuid = selfUuid,
                    listing = listings[group.uuid],
                    removed = removed,
                    behind = behind,
                )
            }

            failure = failure ?: drained

            // Once caught up with the Commit that beat it: a change the user asked for, made anew.
            // Noted in behind once made, which leaves the group to the next pass from there.
            if (group.uuid !in behind) {
                when (val remade = remakeOutdated(group.uuid)) {
                    is Result.Success -> if (remade.data != null) behind += group.uuid

                    is Result.Error -> {
                        behind += group.uuid

                        failure = failure ?: GroupSyncError.Membership(remade.error)
                    }
                }
            }
        }

        return failure
    }

    /**
     * Reads what waits for the user in [group], whose MLS group [mlsGroupId] this device holds, in
     * the order the server lists it, see [readAll], and acts on what that came to: the user removed,
     * which leaves the group on this device read-only, its MLS state deleted, see
     * [GroupEntity.isLeft], and is noted in [removed], naming whoever made the Commit doing so; or
     * else the members the last Commit left and the keys to the members' profiles handed over, see
     * [takeProfileKeys]. Either way, the key of every epoch a Commit took the group to is kept as
     * its generation, the last as the group's, whose copy goes up once the pass is through with
     * every group, see [GroupKeyCopies.putUp], and whose links are checked then, see
     * [checkKeyLinks], the user still in the group, and the signature key each member held in the
     * tree every Commit read left is recorded, see [GroupMemberSignatureKeyEntity]. One whose messages could not all be read is
     * noted in [behind]. One read to the end is held to where the server stands, [listing], see
     * [standing], and noted in [behind] too while this device is behind it.
     *
     * The key of the epoch the group is in is derived first, should this device not hold it: one
     * whose process went away as Commits were read, after the library stored them and before their
     * keys were, which would otherwise never be derived again, MLS giving the key of the epoch a
     * group is in alone.
     *
     * The removal is recorded before anything that could throw: the Commit is spent once read, and
     * one whose reading is lost would leave the group held for good, the user never told. Nor is any
     * key to a member's profile taken from a group the user is no longer in: it vouches for nobody,
     * see [takeProfileKeys].
     *
     * What was read, or refused for good, is acknowledged once acted on, but for a Commit this
     * device refused for the epoch it is at, which its creator's device replaces, see [standing].
     * Not cancellable, which the caller sees to: a message read is consumed, and one whose reading
     * is not acted on is lost.
     */
    private suspend fun drain(
        group: GroupEntity,
        mlsGroupId: ByteArray,
        selfUuid: String,
        listing: GroupDto?,
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

        // Most groups have nothing waiting, most of the time, which leaves them where they stand.
        if (messages.isEmpty()) {
            val standing = standing(
                group = group,
                selfUuid = selfUuid,
                listing = listing,
                epoch = group.keyGeneration,
                refused = emptyList(),
            )

            if (standing.isBehind) behind += group.uuid

            return standing.failure
        }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> {
                behind += group.uuid

                return GroupSyncError.Identity(opened.error)
            }
        }

        val current = try {
            client.epochKeyIn(mlsGroupId = mlsGroupId, groupUuid = group.uuid)
        } catch (_: MlsException) {
            behind += group.uuid

            return GroupSyncError.Storage
        }

        val reading = readAll(
            client = client,
            mlsGroupId = mlsGroupId,
            groupUuid = group.uuid,
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

            val removerUuid = reading.removerUuid ?: group.ownerUuid

            removed += GroupRemoval(
                groupUuid = group.uuid,
                title = group.title,
                removerUuid = removerUuid,
                removerFullName = fullName(removerUuid),
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
        }

        // Recorded as not backed up, for its copy to go up at the end of the pass, see
        // GroupKeyCopies.putUp, or of a later one, unless the user was removed. Every one of them,
        // the newest as the group's key, the user removed or not: one derived and not kept would be
        // a generation this device lacks, which only the chain gives back then, see GroupKeyChain,
        // and which a member removed since can never ask it for, though they read what was sealed
        // under it while they were in. Each owes a look at its links, the one of the epoch the group
        // is in past its key included: Commits of others an earlier pass read led there, see above.
        val derived = listOfNotNull(current.takeIf { it.epoch > group.keyGeneration })
            .map { it.entityOf(groupUuid = group.uuid, isLinkOwed = true) } +
            reading.groupKeys.map { it.entityOf(groupUuid = group.uuid, isLinkOwed = true) }

        if (derived.isNotEmpty()) takeKeys(group.uuid, derived)

        // Whoever a Commit read seated or kept, the user removed or not: what they wrote is
        // checked against it, see GroupMemberSignatureKeyEntity.
        if (reading.signatureKeys.isNotEmpty()) {
            recordSignatureKeys(reading.signatureKeys.entitiesOf(groupUuid = group.uuid))
        }

        // The epoch this device is at once it read all it could.
        val epoch = reading.groupKeys.lastOrNull()?.epoch ?: current.epoch

        // On the creator's device, the Commits refused for that epoch: held back from the
        // acknowledgement until the replacement of the Commit they hold is saved, which names
        // whoever sent them, see standing. They are the one record of who that is, and the next
        // sync, reading them again, refuses them again as this one did.
        val heldBack = reading.refused.filter { it.epoch == epoch }.mapTo(HashSet()) { it.messageUuid }
            .takeIf { group.ownerUuid == selfUuid }
            .orEmpty()

        if (!reading.isRemoved && reading.failure == null) {
            acknowledgeAll(groupUuid = group.uuid, messageUuids = reading.handled - heldBack)
                ?.let { failure = failure ?: it }

            val standing = standing(
                group = group,
                selfUuid = selfUuid,
                listing = listing,
                epoch = epoch,
                refused = reading.refused.filter { it.epoch == epoch },
            )

            if (standing.isBehind) behind += group.uuid

            failure = failure ?: standing.failure

            if (standing.isReplacementSettled) {
                acknowledgeAll(groupUuid = group.uuid, messageUuids = reading.handled.filter { it in heldBack })
                    ?.let { failure = failure ?: it }
            }

            return failure
        }

        // One whose acknowledgement does not go through is read again by the next sync, and
        // refused then, the key it was read with spent: acknowledged as a message that does not read.
        // Or once the user is removed, acknowledged with the Welcome adding them back, see [rejoin].
        // Those held back stay for the pass that reads up to the end, which replaces their Commit.
        acknowledgeAll(groupUuid = group.uuid, messageUuids = reading.handled - heldBack)
            ?.let { failure = failure ?: it }

        return failure
    }

    /**
     * The epoch the first Commit waiting for the user in the group [groupUuid] built in [fromEpoch]
     * or later was built in, as its header says, see [commitEpochOf], or null when no such Commit
     * waits there, see [GroupMembershipSender.settleUnlisted].
     */
    private suspend fun firstCommitEpochIn(groupUuid: String, fromEpoch: Long): Result<Long?, NetworkError> =
        when (val list = listMessages(groupUuid)) {
            is Result.Success -> Result.Success(
                list.data.messages.firstNotNullOfOrNull { message ->
                    runCatching { Base64.Default.decode(message.ciphertext) }.getOrNull()
                        ?.let { commitEpochOf(message = it) }
                        ?.takeIf { it >= fromEpoch }
                },
            )

            is Result.Error -> list
        }

    /**
     * Acknowledges [messageUuids], those of the group [groupUuid] read, or refused for good, and
     * returns what kept it from going through, if anything, see [drain].
     */
    private suspend fun acknowledgeAll(groupUuid: String, messageUuids: List<String>): GroupSyncError? {
        if (messageUuids.isEmpty()) return null

        return when (val acknowledged = acknowledge(groupUuid, messageUuids)) {
            is Result.Success -> null
            is Result.Error -> GroupSyncError.Network(acknowledged.error)
        }
    }

    /**
     * Holds [group], this device at [epoch] once it read everything waiting there, to where the
     * server stands, [listing], or as last listed: this device is behind the server when it refused
     * a Commit for the epoch it is at, [refused], the server storing one per epoch, or when the
     * server's generation is past [epoch], see [GroupDto.keyGeneration], with nothing left to read.
     * Every member's device refused that Commit then, deciding alike about every Commit, see
     * [read], and none of them can write anything the server takes, sealing under a key of an epoch
     * the group has left.
     *
     * The creator's device replaces it, see [GroupMembershipChanger.replaceUnderGroupsLock]: a Commit
     * for the epoch it is at, the one every device stayed in, removing whoever sent the Commits it
     * refused there, as the server says, and whoever departed, see [GroupDto.departed], or nobody,
     * which every member's device takes from it. Only the creator's: any other member's would be
     * refused for removing members who did not depart. Any other member's device records the group as waiting for its owner, see
     * [GroupEntity.isAwaitingOwner], which clears once it is in step again.
     *
     * Says whether this device is still behind the server, whether the replacement is settled, and
     * what kept it from being made, see [Standing]. Settled once saved, to be sent until the server
     * takes it, see [GroupMembershipSender], or refused by the server, which the same request sent
     * again would be too: anything else that kept it from being saved, the key ring not answering
     * say, leaves what the caller held back for it to be read again by the next sync, which tries
     * again. So does a device locking as it reads the vault, which throws, the pass stopping there.
     */
    private suspend fun standing(
        group: GroupEntity,
        selfUuid: String,
        listing: GroupDto?,
        epoch: Long,
        refused: List<RefusedCommit>,
    ): Standing {
        val isBehind = refused.isNotEmpty() || (listing != null && listing.keyGeneration > epoch)

        if (group.ownerUuid != selfUuid) {
            if (isBehind != group.isAwaitingOwner) setAwaitingOwner(group.uuid, isBehind)

            return Standing(isBehind = isBehind, isReplacementSettled = false, failure = null)
        }

        if (!isBehind) return Standing(isBehind = false, isReplacementSettled = false, failure = null)

        val targets = refused.mapTo(HashSet()) { it.senderUuid } +
            listing?.departed.orEmpty().map { it.userUuid } -
            selfUuid

        return when (val replaced = replaceRefused(group.uuid, targets)) {
            // Pending, it waits on the server, as any change of the user's does, see drainAll.
            is Result.Success -> Standing(
                isBehind = replaced.data != MembershipChangeOutcome.MADE,
                isReplacementSettled = true,
                failure = null,
            )

            is Result.Error -> Standing(
                isBehind = true,
                // The one failure a replacement saved comes to: the server refusing it, see
                // GroupMembershipChanger.saveAndSend. Every other is from before it was saved.
                isReplacementSettled = replaced.error is GroupMembershipError.Network,
                failure = GroupSyncError.Membership(replaced.error),
            )
        }
    }

    /**
     * Reads [messages], waiting for the user in the MLS group [mlsGroupId], the one the server holds
     * as [groupUuid], whose creator is [creatorUuid] and whose members are [memberUuids] before the
     * first of them, one after the other in the order the server lists them, see [read]: up to a
     * Commit removing the user, after which there is nothing left to read them with, or up to one
     * that cannot be read yet, which stops the group there, everything after it waiting behind it.
     */
    private suspend fun readAll(
        client: MlsClient,
        mlsGroupId: ByteArray,
        groupUuid: String,
        creatorUuid: String,
        memberUuids: List<String>,
        messages: List<GroupMessageDto>,
    ): GroupReading {
        val handled = mutableListOf<String>()
        var membersAfter: List<String>? = null
        val membersSeen = memberUuids.toHashSet()
        val signatureKeys = mutableMapOf<String, ByteArray>()
        val groupKeys = mutableListOf<EpochKey>()
        val profileKeys = mutableListOf<ProfileKeyHandover>()
        val refused = mutableListOf<RefusedCommit>()

        fun readingOf(isRemoved: Boolean, removerUuid: String?, failure: GroupSyncError?) = GroupReading(
            handled = handled,
            memberUuids = membersAfter,
            memberUuidsSeen = membersSeen,
            signatureKeys = signatureKeys,
            groupKeys = groupKeys,
            profileKeys = profileKeys,
            refused = refused,
            isRemoved = isRemoved,
            removerUuid = removerUuid,
            failure = failure,
        )

        for (message in messages) {
            val reading = read(
                client = client,
                mlsGroupId = mlsGroupId,
                groupUuid = groupUuid,
                creatorUuid = creatorUuid,
                message = message,
            )

            when (reading) {
                is GroupMessageReading.Committed -> {
                    val memberUuidsAfter = reading.members.map { it.userUuid }

                    membersAfter = memberUuidsAfter
                    membersSeen += memberUuidsAfter
                    reading.members.forEach { signatureKeys[it.userUuid] = it.signatureKey }
                    groupKeys += reading.groupKey
                }

                is GroupMessageReading.HandedProfileKeys -> profileKeys += reading.handover

                is GroupMessageReading.RefusedCommit -> refused += RefusedCommit(
                    messageUuid = message.uuid,
                    senderUuid = message.senderUserUuid,
                    epoch = reading.epoch,
                )

                GroupMessageReading.Ignored -> Unit

                is GroupMessageReading.Removed -> {
                    handled += message.uuid

                    return readingOf(isRemoved = true, removerUuid = reading.removerUuid, failure = null)
                }

                is GroupMessageReading.Waiting ->
                    return readingOf(isRemoved = false, removerUuid = null, failure = reading.failure)
            }

            handled += message.uuid
        }

        return readingOf(isRemoved = false, removerUuid = null, failure = null)
    }

    /**
     * What [message], waiting for the user in the MLS group [mlsGroupId], the one the server holds
     * as [groupUuid], comes to, see [GroupMessageReading]. A Commit is taken under the policy every
     * member's device holds the group to, see [processGroupMessageIn]: from the creator,
     * [creatorUuid], whatever it changes, and from anybody else when it removes exactly those the
     * server stored it as removing for having departed, see [GroupMessageDto.departedMemberUuids].
     * The server's list for that message alone, never what this device makes of who departed, so
     * that every member's device decides alike. The creator's application messages hand over the
     * keys to every member's profile, see [profileKeysOf]. Any member can send one, and the one
     * thing taken from anybody else is the key to their own profile, see [ownProfileKeyOf]:
     * whatever else they send, or the creator sends, is of no use to the user.
     */
    private suspend fun read(
        client: MlsClient,
        mlsGroupId: ByteArray,
        groupUuid: String,
        creatorUuid: String,
        message: GroupMessageDto,
    ): GroupMessageReading {
        // The server stores a message without reading it, so this is whatever its sender posted.
        val ciphertext = runCatching { Base64.Default.decode(message.ciphertext) }.getOrNull()
            ?: return GroupMessageReading.Ignored

        val processed = try {
            client.processGroupMessageIn(
                mlsGroupId = mlsGroupId,
                message = ciphertext,
                groupUuid = groupUuid,
                policy = CommitPolicy(creatorUuid = creatorUuid, departedUuids = message.departedMemberUuids),
            )
        } catch (exception: MlsException) {
            return refusalOf(exception = exception, message = ciphertext)
        }

        return when (val content = processed.processed) {
            is ProcessedMessage.Commit -> if (content.removedSelf) {
                // Never absent, the library says, its committer still in the group once applied.
                GroupMessageReading.Removed(removerUuid = content.committerUserUuid)
            } else {
                GroupMessageReading.Committed(
                    members = checkNotNull(processed.membersAfterCommit) {
                        "The members are read after every Commit keeping the user in the group."
                    },
                    groupKey = checkNotNull(processed.keyAfterCommit) {
                        "The key is derived after every Commit keeping the user in the group."
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

    /** What [message], which the library refused with [exception], comes to. */
    private fun refusalOf(exception: MlsException, message: ByteArray): GroupMessageReading = when {
        // Says nothing about the message, which is read again later.
        exception.isRetryable() -> GroupMessageReading.Waiting(failure = GroupSyncError.Storage)

        // A Commit of the creator's seating somebody this device pinned to another identity: taken
        // in, it would have the user trust whoever the server served for them, and every later
        // Commit builds on it.
        exception is MlsException.IdentityKeyMismatch ->
            GroupMessageReading.Waiting(failure = GroupSyncError.IdentityChanged)

        // A Commit the policy refuses, see MlsException.UnauthorizedChange, or a message that is
        // not one, or not to this group, or for an epoch long gone, which never reads. Or one too
        // large to take in, see MlsException.GroupStateTooLarge. Each is refused for good, whoever
        // sent it, a member's key to their profile included, of use on the creator's device as on
        // any other: no group is read while a Commit of the user's is pending in it, see
        // [drainAll], which a message from the epoch it leads to would be refused for want of, and
        // whose settling could make room for one too large. A Commit is told from the rest by its
        // header, a member's rejoin from outside the group among them, see commitEpochOf, which
        // leaves the group behind the server should it be for the epoch the group is in, see
        // [standing].
        else -> commitEpochOf(message = message)
            ?.let { GroupMessageReading.RefusedCommit(epoch = it) }
            ?: GroupMessageReading.Ignored
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
     * Removes from each group of those this device holds in [stored], whoever its tree holds that
     * the server lists as departed in [listings], by UUID, see [GroupDto.departed], all of them in
     * one Commit, through the server's route for them, see
     * [GroupMembershipChanger.removeDepartedUnderGroupsLock], and returns the first failure: a
     * group that fails is left for the next sync, which finds the same members departed, and
     * removes them then.
     *
     * Those are members who left the group, see [GroupApi.leave], on purpose, see [GroupLeaver],
     * or as a device declining it does, and accounts deleted since, which take nobody off a tree.
     * Left there, a leaf keeps one of the seats a group has, is seated by the Welcome of whoever
     * joins next, and holds the key of every epoch until it goes, which opens whatever the members
     * write. A member leaving wakes every member's device for it, see
     * [push.PushSignal.Kind.GROUP_MEMBER_LEFT], so that their leaf goes as soon as they do.
     *
     * The creator's device removes them at once. Any other member's waits until they departed
     * [DEPARTED_GRACE] ago, as the server says, booking the sync for when the newest of them will
     * have, see [ResendScheduler]: the creator, when online, is the one who removes them, and two
     * members committing at once stay rare. Two who do anyway are taken one after the other, the
     * server storing one Commit per epoch: the second hears it is refused for its epoch, see
     * [GroupMembershipError.Outdated], its device reads the first, see [drain], and decides again,
     * the departed member gone by then, unless reading it left the device behind the server, see
     * [standing]. Once at most for a group in a pass.
     *
     * Not a group this device holds no MLS state of, see [restore], nor one the user is no longer
     * in. Nor one the server does not list, nor any the drain noted in [behind]: every group a
     * change of the user's waits on the server in is among them, see [drainAll], whose members are
     * past the epoch this device is in, or will be, every one whose messages could not all be read,
     * which a Commit would leave unread for good, in an epoch it takes the group past, and every one
     * this device is behind the server in with nothing left to read, see [standing], where the
     * server takes no Commit of its epoch, and the creator's replacement removes whoever departed.
     *
     * A removal the server refuses, or has not taken yet, is acted on as any is, see
     * [GroupMembershipSender]: one not taken stays pending, sent again by every sync until it is,
     * and the group is left alone until then. Nobody is told: the server cannot tell a member who
     * declined the group from one who left it, and neither can this device.
     *
     * Under [withGroupsLock], as the whole sync is, so the removal is made without taking it, see
     * [GroupMembershipChanger.removeDepartedUnderGroupsLock].
     */
    private suspend fun dropDeparted(
        listings: Map<String, GroupDto>,
        stored: Map<String, GroupEntity>,
        selfUuid: String,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
    ): GroupSyncError? {
        val departing = stored.values.filter { group ->
            !group.isLeft &&
                group.mlsGroupId != null &&
                group.uuid !in behind &&
                listings[group.uuid]?.departed.orEmpty().any { it.userUuid != selfUuid }
        }

        // Most groups lose nobody, most of the time, and opening the client reads the vault.
        if (departing.isEmpty()) return null

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        var failure: GroupSyncError? = null

        for (group in departing) {
            val listing = listings.getValue(group.uuid)

            val removal = removeDue(client = client, group = group, listing = listing, selfUuid = selfUuid)

            val outcome = if (removal is DepartedRemoval.Outdated) {
                // Read what got there first, then decide again: the departed are gone, most likely.
                val drained = withContext(NonCancellable) {
                    drain(
                        group = group,
                        mlsGroupId = checkNotNull(group.mlsGroupId),
                        selfUuid = selfUuid,
                        listing = listing,
                        removed = removed,
                        behind = behind,
                    )
                }

                if (drained != null) {
                    failure = failure ?: drained

                    continue
                }

                // Caught up and behind all the same, see standing: nothing of that epoch is taken.
                if (group.uuid in behind) continue

                val caughtUp = storedGroups().firstOrNull { it.uuid == group.uuid }?.takeIf { !it.isLeft } ?: continue

                removeDue(client = client, group = caughtUp, listing = listing, selfUuid = selfUuid)
            } else {
                removal
            }

            when (outcome) {
                DepartedRemoval.Made -> Unit
                DepartedRemoval.Outdated -> failure = failure ?: GroupSyncError.Membership(GroupMembershipError.Outdated)
                is DepartedRemoval.Failed -> failure = failure ?: outcome.failure
            }
        }

        return failure
    }

    /**
     * Removes from [group], whose MLS state [client] holds, those [listing] lists as departed whom
     * this device is to remove now, see [dropDeparted], booking the sync for when the others will be
     * due, and says what came of it.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun removeDue(
        client: MlsClient,
        group: GroupEntity,
        listing: GroupDto,
        selfUuid: String,
    ): DepartedRemoval {
        val roster = try {
            client.rosterOf(mlsGroupId = checkNotNull(group.mlsGroupId))
        } catch (_: MlsException) {
            return DepartedRemoval.Failed(failure = GroupSyncError.Storage)
        }

        // Never the user, whatever the server lists: they are in the group, and still syncing it.
        val inTree = listing.departed.filter { it.userUuid != selfUuid && it.userUuid in roster.memberUuids }

        val isCreator = group.ownerUuid == selfUuid
        val now = now()

        // When each of them is due, by user UUID: at once for the creator.
        val dueAt = inTree.associate { departed ->
            val graceOver = Instant.parse(departed.leftAt).toEpochMilliseconds() + DEPARTED_GRACE.inWholeMilliseconds

            departed.userUuid to if (isCreator) now else graceOver
        }

        dueAt.values.filter { it > now }.minOrNull()?.let { soonest ->
            // Rounded up: a sync a second late removes them, one a second early books itself again.
            resendScheduler.resendAfter(seconds = (soonest - now + 999) / 1000)
        }

        val due = dueAt.filterValues { it <= now }.keys

        if (due.isEmpty()) return DepartedRemoval.Made

        return when (val removal = removeDeparted(group.uuid, due)) {
            is Result.Success -> DepartedRemoval.Made

            is Result.Error -> if (removal.error == GroupMembershipError.Outdated) {
                DepartedRemoval.Outdated
            } else {
                DepartedRemoval.Failed(failure = GroupSyncError.Membership(removal.error))
            }
        }
    }

    /**
     * Gives each group brought back after a log-out, of those this device holds in [stored] that
     * the user is still in, the members the server lists of it in [listed], there being no MLS tree
     * on this device to tell them. Its information is taken as any group's is, see [takeEdits].
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
     * Shows, of each group this device holds in [stored] that the user [selfUuid] is still in, the
     * version of its information the server lists in [listed] when it is a newer one its creator
     * signed, see [newerThan], and returns the first failure: a group that fails is left for the
     * next sync, and the next group is seen to all the same.
     *
     * Its title and description, and the revision they are: its currency is the one it was created
     * in, which no edit changes, see [GroupEditor]. Nobody is told: the members' devices pick an
     * edit up as they sync, which the server wakes them for, see
     * [push.PushSignal.Kind.GROUP_CHANGED]. A group the user created takes one too, an edit of
     * theirs whose answer was lost, which the server took.
     *
     * Opened under the key of the generation the listing says it was sealed under, see
     * [GroupDto.informationKeyGeneration], which this device holds once its messages are read, see
     * [drain], or gets back through the chain, see [GroupKeyChain]: a group whose catch-up stopped
     * short of the epoch its creator edited it in shows the newer version once a later sync reads
     * the Commit leading there. A group brought back after a log-out, which reads no Commit, shows
     * none sealed in an epoch past the one its copy holds: what it shows then stays as it is.
     */
    private suspend fun takeEdits(
        listed: List<GroupDto>,
        stored: Map<String, GroupEntity>,
        selfUuid: String,
    ): GroupSyncError? {
        val edited = listed.mapNotNull { listedGroup ->
            stored[listedGroup.uuid]
                ?.takeIf { !it.isLeft && listedGroup.revision > it.infoRevision }
                ?.let { listedGroup to it }
        }

        // Most syncs find no edit, and opening the client reads the vault.
        if (edited.isEmpty()) return null

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        var failure: GroupSyncError? = null

        for ((listedGroup, group) in edited) {
            val creatorKey = try {
                client.creatorKeyOf(creatorUuid = group.ownerUuid, selfUuid = selfUuid)
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage

                continue
            }

            val version = listedGroup.newerThan(group = group, creatorKey = creatorKey) { generation, opens ->
                when (val key = keyOf(group.uuid, generation, opens)) {
                    is Result.Success -> key.data

                    is Result.Error -> {
                        // The version is shown once a later sync reaches the server for the links.
                        (key.error as? GroupKeyChainError.Network)?.let { failure = failure ?: GroupSyncError.Network(it.error) }

                        null
                    }
                }
            }

            version?.let { updateInformation(group.uuid, it) }
        }

        return failure
    }

    /**
     * Marks as left every group brought back after a log-out, of those this device holds in
     * [stored] that the user is still in, that the server no longer lists in [listed], noting it in
     * [removed].
     *
     * The server lists a group to its current members and its owner alone, so one it no longer
     * lists is one whose creator removed the user, see [GroupEntity.isLeft]: one the user left is
     * marked left as they leave it, see [GroupLeaver], and so is never taken for that, and one its
     * owner deleted was marked already, see [takeDeletions], unless the server could not be asked,
     * [untold], which leaves it to the next pass. A group this
     * device holds the MLS state of is told so by the Commit doing so, see [drain], which nothing
     * here can read: a group brought back holds none, see [restore], and so cannot read anything
     * sent in it.
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
        untold: Set<String>,
        removed: MutableList<GroupRemoval>,
    ) {
        val listedUuids = listed.mapTo(HashSet()) { it.uuid }

        for (group in stored.values) {
            if (group.isLeft || group.mlsGroupId != null || group.uuid in listedUuids || group.uuid in untold) continue

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
     * [stored], nor declined, every one it holds that the user was removed from, or left once its
     * leave is no longer owed, and every one brought back after a log-out, see [GroupSync], noting
     * in [joined] those it joined, and in [behind] those whose catch-up stopped short, see [enter],
     * and returns the first failure: a group that fails is left for the next sync, and the next
     * group is taken in all the same.
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

        // Most syncs find nothing new.
        if (invitations.isEmpty()) return null

        // Before anything is joined: a group listed with a copy of its key that opens was the
        // user's before a log-out, and a Welcome still waiting there was sealed to a key package
        // that log-out took with it. Not for a group this device holds, removed from or brought
        // back already. Reads the key ring only when one of them carries a copy.
        val groupKeys = when (val opened = openKeyCopies(invitations.filter { it.uuid !in stored })) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Vault(opened.error)
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
     * Brings back [group], which a log-out took off this device, with [groupKey], the key the
     * user's copy the server lists with it holds, as the generation the server lists that copy as,
     * see [GroupKeyCopies.open]: the version of its information the server lists, at its revision,
     * see [GroupEntity.infoRevision], and its members, without its MLS state, which the log-out
     * took for good, see [GroupEntity.mlsGroupId]. The members are those the server lists, the tree
     * that named them having gone with that state, and so they stay, see [refresh], and no
     * signature key is recorded for any of them. Backed up as it is, the server holding the copy it
     * came from. The version is opened with that key and those the chain leads back to from it,
     * see [openListed]: the generation it was edited in and the first, which its currency is
     * sealed under. The generations between them and the copy's are got back the same way as they
     * are asked for, see [GroupKeyChain].
     *
     * Its creator is the owner the server lists, see [GroupDto.ownerUuid], the Welcome that named
     * them long gone, and the version is held against the pin this device holds for them, see
     * [openSigned]: one of the
     * user's contacts, whose pins the vault brings back as the client opens, or the user, or
     * somebody it takes at the version's word, see [isSignedByCreator]. No change to its members
     * can be made from this device until it rejoins the group, holding no MLS state for it until
     * then, see [GroupRejoiner]: the GroupInfo it rejoins from is asked for as soon as the group is
     * kept, for the other members' devices to publish theirs as they sync, which the server wakes
     * them for, and asked for again by every pass until one is there, see [askAgain]. Not for a
     * group with nobody else in it, whom nobody could publish one for.
     *
     * Information that does not open under those keys, as this group's, was edited in an epoch past
     * the copy's, which this device can never reach, or is not what its members sealed. So is a
     * version its creator did not sign: there being nothing of the group shown on this device to
     * keep instead, it would be shown as theirs. The group is then left where it was, on the
     * server, unless its creator adding the user back lets them in again, see [rejoin]: whatever
     * else waits there while the server lists the group is acknowledged unread, so that it neither
     * holds a key package back from pruning, see [hasNoWelcomeWaiting], nor piles up. One it no
     * longer lists holds none back, and nothing more is sent there, see [leaveUnlisted].
     */
    private suspend fun restore(
        group: GroupDto,
        groupKey: GroupKeyGeneration,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        removed: MutableList<GroupRemoval>,
        behind: MutableSet<String>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        // Opened before the version is held against a pin: opening it is what puts the vault's
        // pins back.
        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        val creatorKey = try {
            client.creatorKeyOf(creatorUuid = group.ownerUuid, selfUuid = selfUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        // The chain from the copy's generation down to the version's and the first, the currency's.
        val isLinked = group.informationKeyGeneration != groupKey.generation ||
            groupKey.generation != FIRST_KEY_GENERATION

        val links = if (isLinked) {
            when (val listed = keyLinks(group.uuid)) {
                is Result.Success -> listed.data
                is Result.Error -> return GroupSyncError.Network(listed.error)
            }
        } else {
            emptyList()
        }

        val opened = openListed(
            group = group,
            held = mapOf(groupKey.generation to groupKey.key),
            links = links,
            creatorUuid = group.ownerUuid,
            creatorKey = creatorKey,
        ) ?: return rejoin(
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
            version = opened.version,
            creatorUuid = group.ownerUuid,
            memberUuids = group.members.map { it.userUuid },
            mlsGroupId = null,
            keys = listOf(groupKey.entityOf(groupUuid = group.uuid, isRecovered = false)) +
                opened.recovered.map { it.entityOf(groupUuid = group.uuid, isRecovered = true) },
            signatureKeys = emptyMap(),
            isBackedUp = true,
            isLeft = false,
            isAwaitingOwner = false,
        )

        if (!kept) return GroupSyncError.Storage

        // As soon as it is kept, for the members' devices to publish the GroupInfo it is rejoined
        // from as they sync: the next pass asks again should this not go through, see GroupRejoiner.
        // Not with nobody else in it, whom nobody could publish one for.
        if (group.hasOtherMemberThan(selfUuid)) requestRejoin(group.uuid)

        return null
    }

    /**
     * Asks once more for a GroupInfo of each group of [groupUuids], those the rejoin found nothing
     * to rejoin from, or nothing to trust, see [GroupRejoinPass.unanswered], that is still brought
     * back after a log-out once the pass took in what it could, see [GroupApi.requestRejoin]: which
     * keeps the request from lapsing, and has the server wake the members' devices again should it
     * have. Returns the first failure, a `404` being none, see [GroupRejoiner.rejoinUnderGroupsLock].
     *
     * Not a group this pass let the user in again from the Welcome of its creator adding them back,
     * see [rejoin], whose removal closed the request, nor one it found them removed from, see
     * [leaveUnlisted]: asked again, the request would stand until it lapsed, and have every other
     * member's device publish a GroupInfo at each epoch until then for nobody.
     */
    private suspend fun askAgain(groupUuids: Set<String>): GroupSyncError? {
        if (groupUuids.isEmpty()) return null

        var failure: GroupSyncError? = null

        for (group in storedGroups()) {
            if (group.uuid !in groupUuids || group.isLeft || group.mlsGroupId != null) continue

            when (val requested = requestRejoin(group.uuid)) {
                is Result.Success -> Unit
                Result.Error(NetworkError.NOT_FOUND) -> Unit
                is Result.Error -> failure = failure ?: GroupSyncError.Network(requested.error)
            }
        }

        return failure
    }

    /**
     * The version of its information [group] lists, opened with [held], keys of the group's by
     * generation, and those [links], every candidate the server lists for the chain back through
     * its keys, lead back to from them, see [recoverKeys]: its title, description and signature
     * under the generation it was written in, see [GroupDto.informationKeyGeneration], and its
     * currency under the first, see [SealedGroupFields], when its creator, [creatorUuid], signed it
     * under [creatorKey], see [openSigned]; with every key the chain led back to on the way. Null
     * when either key cannot be had, the version does not open under them, or its creator did not
     * sign it.
     */
    private fun openListed(
        group: GroupDto,
        held: Map<Long, ByteArray>,
        links: List<GroupKeyLinkDto>,
        creatorUuid: String,
        creatorKey: ByteArray?,
    ): ListingOpened? {
        val sealed = group.sealedVersion()

        val information = keyAmong(
            groupUuid = group.uuid,
            generation = group.informationKeyGeneration,
            held = held,
            links = links,
        ) { key -> sealed.opensUnder(groupKey = key, groupUuid = group.uuid) } ?: return null

        val currency = keyAmong(
            groupUuid = group.uuid,
            generation = FIRST_KEY_GENERATION,
            held = held + information.recovered.associate { it.generation to it.key },
            links = links,
        ) { key -> openCurrency(currencyKey = key, groupUuid = group.uuid, sealed = sealed.fields.currency) != null }
            ?: return null

        val version = sealed.openSigned(
            groupKey = information.key,
            currencyKey = currency.key,
            groupUuid = group.uuid,
            creatorUuid = creatorUuid,
            creatorKey = creatorKey,
        ) ?: return null

        return ListingOpened(version = version, recovered = information.recovered + currency.recovered)
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
     * declined without being joined unless the member who wrote it may add the user to a group,
     * see [mayAdd], whoever the server says sent it, and once joined unless the keys the group
     * gives open it to a version of its information they signed, see [enter]. When only who wrote
     * it does not hold up, it is left waiting instead unless [contactsTakenIn], see [sync].
     *
     * The first one waiting can be no way in at all, though, and then says nothing of who adds the
     * user: what waited for them in an earlier membership of theirs that no device of theirs read
     * to the end, the Commit removing them among it, as once its creator removed them while they
     * were logged out, and added them back since. The server dropped their copy of the group's key
     * with the removal, see [GroupKeyCopies], so nothing here says the group was theirs, as a copy
     * that opens does, see [restore]. Its creator is taken to be the one the server lists then,
     * as for a group brought back after a log-out, see [rejoin], and must hold up as any creator
     * does: only a Welcome of theirs is joined from, never a member's to a group of their own. With
     * none of theirs after it, the group is declined, as one whose first Welcome is no way in, one
     * sealed to a key package a log-out took say, always was.
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
        // Welcome but those its owner writes adding them, and lists no copy of its key that opens,
        // as for a group joined before a log-out whose copy never went up. One declined before a
        // log-out and not left yet still has its Welcome waiting, sealed to a key package that
        // log-out took, which the inspection refuses.
        if (messages.isEmpty()) return decline(groupUuid = group.uuid)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        val first = try {
            welcomeAt(client = client, groupUuid = group.uuid, messages = messages, index = 0)
        } catch (_: MlsException) {
            // Storage failing says nothing about the Welcome, so that one is tried again.
            return GroupSyncError.Storage
        }

        // No way in at all, the first says nothing of who adds the user, see above.
        val creatorUuid = first?.inspection?.committerUserUuid ?: group.ownerUuid

        val creatorMayAdd = try {
            mayAdd(client = client, userUuid = creatorUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        if (!creatorMayAdd) {
            // Declining is for good: not while they may be a contact this device has yet to hear of.
            return if (contactsTakenIn) decline(groupUuid = group.uuid) else null
        }

        val latest = try {
            latestWelcome(
                client = client,
                groupUuid = group.uuid,
                creatorUuid = creatorUuid,
                messages = messages,
                from = 1,
            )
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        val welcome = latest ?: first ?: return decline(groupUuid = group.uuid)

        return enter(
            group = group,
            client = client,
            welcome = welcome,
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
     * [joined]; or leaves it as it is, see [passOver]. One this device records the user as no
     * longer in, removed or left, whose MLS group was [heldMlsGroupId], or one it holds no MLS
     * state for, brought back after a log-out, see [restore], which being added back is the only
     * way to MLS state again.
     *
     * The way back in is the last Welcome waiting that [creatorUuid] wrote, see [latestWelcome]:
     * whoever this device recorded as the group's creator, see [GroupEntity.ownerUuid], or the
     * creator the server lists for one it does not hold, and they must still be somebody who may add
     * the user to a group, see [mayAdd]. Whatever this device could not acknowledge as it read the
     * Commit removing the user, that Commit included, may still wait before it, and so does
     * whatever was sent in a group with no MLS state to read it with: it is acknowledged with it.
     * What comes after it is caught up with, and the row brought back, with what the Welcome hands
     * over, see [enter].
     *
     * The MLS state of [heldMlsGroupId] is deleted again first, should the deletion as the user
     * was removed, see [drain], or left, see [GroupLeaver], not have gone through: held, it would
     * refuse every Welcome to the group, its creator's adding the user back included, see
     * [MlsClient.inspectWelcome].
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
     * The one of [messages] at [index] when it is a Welcome to the group [groupUuid] this device
     * can join from, see [inspectGroupWelcome], or null when it is no way in at all: one that was
     * never the user's, was already used, is to another group, seats an identity other than the
     * one pinned for a member or more members than a group may hold, or is no Welcome, nor even
     * Base64, the server storing a message without reading it.
     *
     * Throws [MlsException] when the user's storage fails, which says nothing of the message.
     */
    private suspend fun welcomeAt(
        client: MlsClient,
        groupUuid: String,
        messages: List<GroupMessageDto>,
        index: Int,
    ): WaitingWelcome? {
        val welcomeMessage = runCatching { Base64.Default.decode(messages[index].ciphertext) }.getOrNull()
            ?: return null

        val inspection = try {
            client.inspectGroupWelcome(welcome = welcomeMessage, groupUuid = groupUuid)
        } catch (exception: MlsException) {
            if (exception.isRetryable()) throw exception else return null
        }

        return WaitingWelcome(index = index, message = welcomeMessage, inspection = inspection)
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
            // No Welcome at all, or no way in: one before it may still be.
            val welcome = welcomeAt(client = client, groupUuid = groupUuid, messages = messages, index = index)
                ?: continue

            if (welcome.inspection.committerUserUuid == creatorUuid) return welcome
        }

        return null
    }

    /**
     * Joins [group] from [welcome], one of [messages], which its inspection found worth joining,
     * catches up with what waits after it, see [readAll], and saves the group as it stands then,
     * noting it in [joined], or in [removed] when one of those messages removed the user again,
     * and in [behind] when one of them could not be read yet; or [refuse]s the way in when the
     * version of the group's information the server lists does not open to one its creator signed
     * under the keys the group gave this device, and those the chain leads back to from them. What
     * waits before the Welcome is acknowledged with it, see [latestWelcome].
     *
     * The key of the epoch the Welcome lets the user in at is the one MLS derives once it is
     * joined, see [epochKeyIn], and each Commit after it gives the next. The version the server lists
     * is sealed under the generation it was edited in, see [GroupDto.informationKeyGeneration], and
     * its currency under the first, which a member joining later than either gets back through the
     * chain, see [openListed]: the candidates for its links are asked for before anything is joined,
     * should the group be past its first epoch, a server not answering leaving the Welcome waiting
     * for the next sync. The key that opens it, as this group's, see [SealedGroupVersion.unseal], is
     * also what refuses a key that is not 32 bytes. Opened, it has to be one the Welcome's writer
     * signed, see [openSigned], whose pin [mayAdd] found, read before anything is joined: another
     * member's, which the server lists, is refused as a version that does not open is, there being
     * nothing of the group shown on this device to keep instead. It is kept at the revision the
     * server lists, see [GroupEntity.infoRevision], whatever this device showed of a group it holds
     * already, which it joins anew. The key kept as the group's is that of the last epoch reached,
     * not backed up yet: its copy goes up once the pass is through with every group, see
     * [GroupKeyCopies.putUp], unless the catch-up removed the user again, see [drain]. Every one
     * the group gave, or the chain led back to, is kept as its generation, see [GroupKeyEntity], and
     * the signature key each member held in the Welcome's tree and every Commit after it, see
     * [GroupMemberSignatureKeyEntity]. A group the catch-up leaves behind the server, see
     * [standing], is kept as waiting for its owner, see [GroupEntity.isAwaitingOwner].
     *
     * The keys to the members' profiles are in the payload of the Welcome, see [welcomeHandoverOf],
     * and are taken with those the catch-up read, see [takeProfileKeys], once the group is saved,
     * and before anything is acknowledged, the creator's own as of when the server stored the
     * Welcome. A payload that does not read as one hands over none. The Welcome it came in is to this
     * group and no other, the inspection having refused any other, and only whoever wrote that
     * Welcome can have put it there.
     *
     * Saved with the members the group holds once caught up, the user among them, as the Welcome's
     * tree and every Commit after it name them, rather than those the server lists, see
     * [joinGroupOf], and whoever wrote the Welcome as its creator rather than whom the server lists,
     * see [GroupEntity.ownerUuid]. Joined, caught up and saved under [withGroupJoinLock], so
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
        val handover = inspection.welcomePayload?.let { welcomeHandoverOf(payload = it) }

        // Before the join, which nothing after may throw past: a Welcome joined and not saved is lost.
        val creatorKey = try {
            client.creatorKeyOf(creatorUuid = creatorUuid, selfUuid = selfUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        // Before the join too, for the same reason: a group past its first epoch is entered past it.
        // Refused to a caller who is no current member, removed since the group was listed: what
        // waits after the Welcome ends with the removal, and nothing past it is to be opened.
        val links = if (group.keyGeneration > FIRST_KEY_GENERATION) {
            when (val listed = keyLinks(group.uuid)) {
                is Result.Success -> listed.data
                Result.Error(NetworkError.NOT_FOUND) -> emptyList()
                is Result.Error -> return GroupSyncError.Network(listed.error)
            }
        } else {
            emptyList()
        }

        val entry = try {
            withGroupJoinLock<Entry> {
                val mlsGroupId = client.joinGroupOf(
                    welcome = welcome.message,
                    groupUuid = group.uuid,
                    expectedMembers = inspection.memberUuids,
                )

                // The Welcome's tree and key, before anything after it changes them. Nothing past the
                // join may throw: storage failing here leaves the signature keys to the Commits after
                // it, and to the pins, and the key to them too, or to nothing at all.
                val seated = try {
                    client.rosterOf(mlsGroupId = mlsGroupId).members
                } catch (_: MlsException) {
                    emptyList()
                }

                val welcomeKey = try {
                    client.epochKeyIn(mlsGroupId = mlsGroupId, groupUuid = group.uuid)
                } catch (_: MlsException) {
                    null
                }

                val caughtUp = readAll(
                    client = client,
                    mlsGroupId = mlsGroupId,
                    groupUuid = group.uuid,
                    creatorUuid = creatorUuid,
                    memberUuids = inspection.memberUuids + selfUuid,
                    messages = after,
                )

                // In the order the epochs come in, the last the group's.
                val derived = listOfNotNull(welcomeKey?.entityOf(groupUuid = group.uuid, isLinkOwed = false)) +
                    caughtUp.groupKeys.map { it.entityOf(groupUuid = group.uuid, isLinkOwed = true) }

                val opened = derived.takeIf { it.isNotEmpty() }?.let { keys ->
                    openListed(
                        group = group,
                        held = keys.associate { it.generation to it.groupKey },
                        links = links,
                        creatorUuid = creatorUuid,
                        creatorKey = creatorKey,
                    )
                }

                if (opened == null) {
                    client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

                    // No key at all is the user's storage failing, see above, and no refusal.
                    return@withGroupJoinLock if (derived.isEmpty()) Entry.Lost else Entry.Refused
                }

                val epoch = derived.last().generation

                val kept = keep(
                    group = group,
                    version = opened.version,
                    creatorUuid = creatorUuid,
                    memberUuids = caughtUp.memberUuids ?: (inspection.memberUuids + selfUuid),
                    mlsGroupId = mlsGroupId,
                    keys = derived + opened.recovered.map { it.entityOf(groupUuid = group.uuid, isRecovered = true) },
                    // A Commit's after the Welcome's, each member as the group last held them.
                    signatureKeys = seated.associate { it.userUuid to it.signatureKey } + caughtUp.signatureKeys,
                    isBackedUp = false,
                    isLeft = caughtUp.isRemoved,
                    // The user never created a group they join: its creator's device replaces it.
                    isAwaitingOwner = !caughtUp.isRemoved && caughtUp.failure == null &&
                        (caughtUp.refused.any { it.epoch == epoch } || group.keyGeneration > epoch),
                )

                // Not kept, the Welcome is spent all the same, so the group goes, and the next sync
                // finds the Welcome refused as one already joined. Removed again, the user has no
                // use for its state either, see [drain].
                if (!kept || caughtUp.isRemoved) client.deleteGroupQuietly(mlsGroupId = mlsGroupId)

                if (kept) Entry.Kept(fields = opened.version.fields, caughtUp = caughtUp) else Entry.Lost
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
            val removerUuid = kept.caughtUp.removerUuid ?: creatorUuid

            removed += GroupRemoval(
                groupUuid = group.uuid,
                title = kept.fields.title,
                removerUuid = removerUuid,
                removerFullName = if (removerUuid == creatorUuid) creatorFullName else fullName(removerUuid),
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
     * it a group to leave, and [leaveDeclined] leaves it later in the same pass. Nothing waiting there
     * is acknowledged: the leave drops all of it, the Welcome among it, and whatever comes after it
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
     * Refuses the way back into the group [groupUuid] the user was removed from or left, or
     * brought back after a log-out, every message waiting there, [messages], acknowledged, and
     * leaves the group on this device as it is, if at all, see [restore]. Not declined, see
     * [decline], which would leave the group, and refuse the Welcome of its creator adding the user
     * back some later day.
     */
    private suspend fun passOver(groupUuid: String, messages: List<GroupMessageDto>): GroupSyncError? {
        acknowledge(groupUuid, messages.map { it.uuid }).onError { return GroupSyncError.Network(it) }

        return null
    }

    /**
     * Sends every leave owed, see [DeclinedGroupEntity]: that of every group this device declined,
     * and of every group the user left, see [GroupLeaver]. Each is forgotten once the server took
     * it, or said the user was never in a group it no longer lists, and what kept a leave from
     * going through is returned: the first one the server did not answer stops them all, the next
     * faring no better, and books them all again, see [UNANSWERED_LEAVE_RESEND_SECONDS].
     *
     * A `404` for a group in [listed] is no answer either. The server lists a group to its current
     * members alone, and takes the leave of every one of them but its owner, whom it refuses with a
     * `403`, so that `404` can only be the proxy in front of it answering as it is redeployed, the
     * user still a member, see [GroupLeaveAnswer.NotMember]. Forgotten, the leave of a group the
     * user left would never be sent again, the group being taken in again rather than left once
     * no leave is owed for it, see [takeIn], and the server would keep them a member, never listing
     * them as departed nor waking anybody, so that no member's device would ever remove their leaf,
     * see [dropDeparted]. The proxy answers so only until the redeploy is done, so the leave is not
     * sent again for good.
     *
     * Forgotten, a group this device declined is decided on afresh should the server list it
     * again: its creator added the user back, whose new Welcome is the first thing waiting then,
     * the leave having dropped everything before it. One the user left, which this device still
     * holds, is joined again from its creator's Welcome adding them back, as after a removal, see
     * [rejoin], and its leave is never sent again. A leave the server took whose answer was lost is
     * sent again, and leaves again a group whose creator added the user back in between: that
     * takes their creator syncing and adding them back before the leave goes out again, which is
     * accepted.
     *
     * Nothing is forgotten before the server answered, so a device locking as this runs, see
     * [GroupSyncError.DeviceLocked], leaves every group not left yet owed for the next pass.
     */
    private suspend fun leaveDeclined(listed: List<GroupDto>): GroupSyncError? {
        val listedUuids = listed.mapTo(HashSet()) { it.uuid }

        for (groupUuid in declinedGroups()) {
            // What kept the leave from going through, if anything: a 404 for a group listed is
            // the proxy's, see above.
            val unanswered = when (val answer = leave(groupUuid)) {
                GroupLeaveAnswer.Left -> null
                GroupLeaveAnswer.NotMember -> NetworkError.NOT_FOUND.takeIf { groupUuid in listedUuids }
                is GroupLeaveAnswer.Failed -> answer.error
            }

            if (unanswered != null) {
                resendScheduler.resendAfter(seconds = UNANSWERED_LEAVE_RESEND_SECONDS)

                return GroupSyncError.Network(unanswered)
            }

            forgetDeclined(groupUuid)
        }

        return null
    }

    /**
     * Saves [group] with the [version] of its information it lists, [creatorUuid] as its creator,
     * its members, [memberUuids], the creator their admin, in place of whatever this device held
     * of it, [keys] among the generations of its key, the newest as its key, the signature key each
     * of [signatureKeys] signs with, by user UUID, and whether it waits for its owner,
     * [isAwaitingOwner], see [GroupEntity.isAwaitingOwner], and says whether it was saved.
     */
    private suspend fun keep(
        group: GroupDto,
        version: GroupVersion,
        creatorUuid: String,
        memberUuids: List<String>,
        mlsGroupId: ByteArray?,
        keys: List<GroupKeyEntity>,
        signatureKeys: Map<String, ByteArray>,
        isBackedUp: Boolean,
        isLeft: Boolean,
        isAwaitingOwner: Boolean,
    ): Boolean {
        val groupKey = keys.maxBy { it.generation }

        val entity = GroupEntity(
            uuid = group.uuid,
            title = version.fields.title,
            description = version.fields.description,
            currency = version.fields.currency,
            infoRevision = version.revision,
            ownerUuid = creatorUuid,
            mlsGroupId = mlsGroupId,
            groupKey = groupKey.groupKey,
            keyGeneration = groupKey.generation,
            isBackedUp = isBackedUp,
            isLeft = isLeft,
            // Owed, as every group the user joins is, see [takeProfileKeys].
            handedProfileKeyHash = null,
            isAwaitingOwner = isAwaitingOwner,
        )

        return try {
            save(
                entity,
                membersOf(groupUuid = group.uuid, memberUuids = memberUuids, creatorUuid = creatorUuid),
                keys,
                signatureKeys.entitiesOf(groupUuid = group.uuid),
            )

            true
        } catch (_: Exception) {
            false
        }
    }
}

/** What one message waiting in a group came to, see [GroupSync.read]. */
private sealed interface GroupMessageReading {

    /**
     * A Commit the group's policy takes, applied: the group now holds [members], the user among
     * them, as its tree holds them, and is in the epoch whose key is [groupKey].
     */
    class Committed(val members: List<MemberInfo>, val groupKey: EpochKey) : GroupMessageReading

    /**
     * A Commit removing the user, made by [removerUuid], after which the group cannot be read in.
     * The library always names them, see [ProcessedMessage.Commit.committerUserUuid].
     */
    class Removed(val removerUuid: String?) : GroupMessageReading

    /**
     * A Commit refused for good, built in [epoch], as its header says, see [commitEpochOf]:
     * acknowledged as [Ignored] is, but for one the creator's device replaces, see
     * [GroupSync.standing].
     */
    class RefusedCommit(val epoch: Long) : GroupMessageReading

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

    /**
     * The signature key each member held in the tree of the last Commit read that held them, by
     * user UUID, those it removed since among them: empty when no Commit was read.
     */
    val signatureKeys: Map<String, ByteArray>,

    /** The key of every epoch a Commit read took the group to, in the order read. */
    val groupKeys: List<EpochKey>,

    /** Every key to a member's profile handed over, in the order handed over, see [ProfileKeyHandover]. */
    val profileKeys: List<ProfileKeyHandover>,

    /** Every Commit refused for good, in the order read, see [GroupMessageReading.RefusedCommit]. */
    val refused: List<RefusedCommit>,

    /** Whether a Commit removed the user, the last message read then. */
    val isRemoved: Boolean,

    /** Who made the Commit that removed the user, when one did and the library named them. */
    val removerUuid: String?,

    /** What kept the messages after the last one read from being read, see [GroupMessageReading.Waiting]. */
    val failure: GroupSyncError?,
)

/**
 * A Commit refused for good, see [GroupMessageReading.RefusedCommit]: the message [messageUuid]
 * holding it, whom the server says sent it, [senderUuid], and the epoch it was built in, [epoch].
 */
private class RefusedCommit(val messageUuid: String, val senderUuid: String, val epoch: Long)

/**
 * Where a group stands against the server, see [GroupSync.standing]: whether this device is still
 * behind it, [isBehind], none of whose Commits the server would take, so that the pass leaves the
 * group alone from there, see [GroupSync.dropDeparted]; whether the creator's device is done with
 * the Commits it refused for the epoch it is at, [isReplacementSettled], its replacement saved or
 * refused by the server; and what kept the replacement from being made, if anything.
 */
private class Standing(val isBehind: Boolean, val isReplacementSettled: Boolean, val failure: GroupSyncError?)

/** What removing those who departed from a group came to, see [GroupSync.removeDue]. */
private sealed interface DepartedRemoval {

    /** Made, or nothing to make now, or pending on the server, see [MembershipChangeOutcome]. */
    data object Made : DepartedRemoval

    /** Refused for its epoch, another member's Commit having got there first. */
    data object Outdated : DepartedRemoval

    /** Not made, for [failure]. */
    class Failed(val failure: GroupSyncError) : DepartedRemoval
}

/**
 * The version of a group's information the server lists, opened, see [GroupSync.openListed], with
 * every key the chain led back to on the way, [recovered].
 */
private class ListingOpened(val version: GroupVersion, val recovered: List<GroupKeyGeneration>)

/** The key of a generation, [key], with every key the chain led back to on the way, [recovered], see [keyAmong]. */
private class ChainedKey(val key: ByteArray, val recovered: List<GroupKeyGeneration>)

/**
 * The key of [generation] of the group [groupUuid]'s keys among [held], by generation, or else the
 * one [links] lead back to from the nearest generation after it held, see [recoverKeys], [opens]
 * saying what is sealed under it; or null when it cannot be had: what a group being joined, or
 * brought back, is opened with before anything of it is saved, see [GroupKeyChain.keyOf] for one
 * that is.
 */
private fun keyAmong(
    groupUuid: String,
    generation: Long,
    held: Map<Long, ByteArray>,
    links: List<GroupKeyLinkDto>,
    opens: (key: ByteArray) -> Boolean,
): ChainedKey? {
    held[generation]?.let { return ChainedKey(key = it, recovered = emptyList()) }

    val nearestAfter = held.keys.filter { it > generation }.minOrNull() ?: return null

    val recovered = recoverKeys(
        groupUuid = groupUuid,
        generation = generation,
        from = GroupKeyGeneration(generation = nearestAfter, key = held.getValue(nearestAfter)),
        links = links,
        opens = opens,
    ) ?: return null

    return ChainedKey(key = recovered.last().key, recovered = recovered)
}

/** What joining a group came to, see [GroupSync.enter]. */
private sealed interface Entry {

    /** Joined and saved, with the [fields] it opened to and what [caughtUp] read. */
    class Kept(val fields: GroupFields, val caughtUp: GroupReading) : Entry

    /**
     * Joined, and deleted again, the information opening under no key the group gave, or the chain
     * led back to, to a version its creator signed.
     */
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

/**
 * This generation of the key of the group [groupUuid], as it is kept, see [GroupKeyEntity], as the
 * chain gave it when [isRecovered], see [GroupKeyEntity.isRecovered].
 */
private fun GroupKeyGeneration.entityOf(groupUuid: String, isRecovered: Boolean) =
    GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = key, isRecovered = isRecovered)

/**
 * The key of this epoch of the group [groupUuid], as it is kept, see [GroupKeyEntity], owing a look
 * at its links when [isLinkOwed], see [GroupKeyEntity.isLinkOwed].
 */
private fun EpochKey.entityOf(groupUuid: String, isLinkOwed: Boolean) =
    GroupKeyEntity(groupUuid = groupUuid, generation = epoch, groupKey = key, isLinkOwed = isLinkOwed)

/** These signature keys of the members of the group [groupUuid], by user UUID, as they are kept. */
private fun Map<String, ByteArray>.entitiesOf(groupUuid: String) =
    map { (userUuid, signatureKey) ->
        GroupMemberSignatureKeyEntity(groupUuid = groupUuid, userUuid = userUuid, signatureKey = signatureKey)
    }

/**
 * The signature key each of these members, as the tree of the group [groupUuid] holds them, signs
 * with there, as it is kept, see [GroupMemberSignatureKeyEntity].
 */
internal fun List<MemberInfo>.signatureKeysIn(groupUuid: String) =
    associate { it.userUuid to it.signatureKey }.entitiesOf(groupUuid = groupUuid)

/**
 * When the server stored this message, in milliseconds since the epoch: the one clock every message
 * of every group is stamped by, which it lists them in the order of. The API writes it for each
 * message it lists, in UTC, to the millisecond, see [GroupMessageDto.createdAt].
 */
@OptIn(ExperimentalTime::class)
private fun GroupMessageDto.sentAt(): Long = Instant.parse(createdAt).toEpochMilliseconds()
