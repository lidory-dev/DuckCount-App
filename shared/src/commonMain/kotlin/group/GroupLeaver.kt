package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.GroupEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import message.ResendScheduler
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import push.WakeList
import uniffi.lib_duckcount.MlsClient
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Takes the user out of a group they did not create, as they ask from its settings: on this device
 * first, then on the server. The group stays on this device, read-only, with what the user had of
 * it, as after a removal, see [GroupEntity.isLeft]: its expenses as this device last synced them,
 * see [GroupExpenseSync], which the server lists to its current members alone, and which a log-out
 * takes with it.
 *
 * In this order, under [withGroupsLock] as a sync and a change to a group's members are: a sync
 * draining the group, or rejoining it, as this runs would act on what this is halfway through. The
 * lock is not reentrant, so this is never called from under it: the screens call it, the sync
 * never does.
 * 1. The group is read again, as it stands now. One the user is no longer in already, a sync
 *    having just read the Commit removing them, is left as it is, with nothing sent: that sync
 *    tells them of the removal. One they created is refused, see [GroupLeaveError.IsCreator], with
 *    nothing written.
 * 2. The group is marked left and its leave recorded as owed, in one transaction, see
 *    [GroupDao.markLeftAndOweLeave] and [DeclinedGroupEntity], which drops any removal of those
 *    who departed this device has waiting on the server there. This is what the leave comes to on
 *    this device, whatever follows: from here no sync drains the group, gives it the members the
 *    server lists, or takes it for one its creator removed the user from, nor takes it in again
 *    while the leave is owed, see [GroupSync], so no removal is ever told about for it. Before
 *    anything is sent, as a decline is recorded first: a leave sent and never recorded would
 *    leave the group current here for good, the server sending a member who left nothing more,
 *    the creator's Commit removing them included. Who may wake the user is rebuilt then, see
 *    [WakeList]: its members no longer do, but for those in another of their groups, and their
 *    contacts.
 * 3. Its MLS state is deleted, when this device holds it, as for a removal. A deletion that fails
 *    leaves the state to be deleted before the user is let back in, see [GroupSync]. The row
 *    keeps the group's id, so that a direct message naming it is still never read, see
 *    [GroupEntity.mlsGroupId].
 * 4. The user's copy of its key is dropped from the server, see [GroupApi.deleteKeyCopy], as best
 *    it can be. The server drops it as it takes the leave, but a log-out while the leave is owed
 *    forgets that it is, and the next session, finding the group listed with the copy still
 *    opening it, would bring it back as a group the user is in. With no copy there, it is declined
 *    and left again instead. Nothing else needs that copy: a group the user is no longer in is
 *    never brought back after a log-out, and the creator adding them back lets them derive the key
 *    of the epoch the group is at then, whose copy goes up as they rejoin, see
 *    [GroupKeyCopies.putUp].
 * 5. The leave is sent, see [GroupApi.leave], and forgotten once the server took it. One the
 *    server was not heard to take is booked to go out again, see
 *    [UNANSWERED_LEAVE_RESEND_SECONDS]: the sync sends every leave owed, see
 *    [GroupSync.leaveDeclined], once the device is unlocked. So is one answered `404`, which may
 *    be the proxy in front of the server answering in its place as it is redeployed, the user
 *    still a member there, see [GroupLeaveAnswer.NotMember]. With no listing of the user's
 *    groups to tell the proxy's from the server's own, this leaves it to the sync, which forgets
 *    the leave once the server lists the group no more. Forgotten here, the leave would never be
 *    sent again: the sync takes a group the user left back in, rather than leaving it, once no
 *    leave is owed for it, and the server would keep them a member, never listing them as
 *    departed nor waking anybody, so that no member's device would ever remove their leaf.
 *
 * Done once the leave is recorded, whatever the server did: the leave is the user's from then on,
 * and the network only decides when the server hears it. From there to the answer is not
 * cancellable, so that the screen going away does not leave the leave to wait on a sync nothing
 * booked.
 *
 * The leave can wait on a sync under way for as long as that sync takes, and the device can lock
 * meanwhile, which leaves nothing this device stores readable. Before the leave is recorded,
 * nothing is done then, see [GroupLeaveError.DeviceLocked]. After, an MLS client that cannot open
 * leaves the state as a deletion that failed does, and the leave goes out all the same: a request
 * the locked device cannot send reads as no answer, booked to go out again.
 *
 * As the server takes a current member's leave, it lists them as departed, and wakes every member
 * who remains, see [push.PushSignal.Kind.GROUP_MEMBER_LEFT]: the creator's device removes the user
 * from the group's MLS group at once, and any other member's once [DEPARTED_GRACE] has gone by,
 * see [GroupSync], the key of the epoch that leads to being one the user never derives. Nobody is
 * told, as for a device declining the group, which no member's device can tell from a leave.
 *
 * The creator is who this device recorded as the group's, see [GroupEntity.ownerUuid], not the
 * server's owner, which a deletion of the creator's account hands to another member. A member
 * handed a group that way may still ask to leave it, which the server refuses its owner: the
 * refusal reads as no answer, and the sync booked for it leaves the leave owed without sending it
 * again, the server listing them as the group's owner, see [GroupSync.leaveDeclined], so the
 * request goes out once. The group shows as left on their device while the server keeps them its
 * owner, which changes nothing: no member takes a Commit from anybody but the group's creator, but
 * for a removal of those who departed, so that group changes no further than that anyway.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] is [GroupDao.get], [markLeftAndOweLeave] and
 * [forgetOwedLeave] are [GroupDao.markLeftAndOweLeave] and [GroupDao.deleteDeclined],
 * [rebuildWakeList] is [WakeList.rebuild], [client] is [MlsIdentityRepository.client],
 * [deleteKeyCopy] is [GroupApi.deleteKeyCopy] and [sendLeave] is [GroupApi.leave], or what a test
 * stands in for any of them, as [resendScheduler] is for the scheduler bound on this platform.
 */
class GroupLeaver internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val markLeftAndOweLeave: suspend (groupUuid: String) -> Unit,
    private val rebuildWakeList: suspend () -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val deleteKeyCopy: suspend (groupUuid: String) -> EmptyResult<NetworkError>,
    private val sendLeave: suspend (groupUuid: String) -> GroupLeaveAnswer,
    private val forgetOwedLeave: suspend (groupUuid: String) -> Unit,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        wakeList: WakeList,
        resendScheduler: ResendScheduler,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        group = groupDao::get,
        markLeftAndOweLeave = groupDao::markLeftAndOweLeave,
        rebuildWakeList = wakeList::rebuild,
        client = mlsIdentityRepository::client,
        deleteKeyCopy = groupApi::deleteKeyCopy,
        sendLeave = groupApi::leave,
        forgetOwedLeave = groupDao::deleteDeclined,
        resendScheduler = resendScheduler,
    )

    /** Takes the user out of the group [groupUuid], see [GroupLeaver]. */
    suspend fun leave(groupUuid: String): Result<Unit, GroupLeaveError> = withGroupsLock {
        // The device may have locked as this waited for the lock, see above.
        val selfUuid = try {
            userUuid()
        } catch (_: IllegalStateException) {
            return@withGroupsLock Result.Error(GroupLeaveError.DeviceLocked)
        } ?: return@withGroupsLock Result.Error(GroupLeaveError.NoSession)

        val held = group(groupUuid) ?: return@withGroupsLock Result.Error(GroupLeaveError.NotHeld)

        if (held.isLeft) return@withGroupsLock Result.Success(Unit)

        if (held.ownerUuid == selfUuid) return@withGroupsLock Result.Error(GroupLeaveError.IsCreator)

        withContext(NonCancellable) { leaveHeld(held) }
    }

    /** Leaves [held], a group the user is in and did not create, from step 2 on, see [GroupLeaver]. */
    private suspend fun leaveHeld(held: GroupEntity): Result<Unit, GroupLeaveError> {
        try {
            markLeftAndOweLeave(held.uuid)
        } catch (_: Exception) {
            return Result.Error(GroupLeaveError.Storage)
        }

        // Its members wake the user no longer, see above.
        try {
            rebuildWakeList()
        } catch (_: IllegalStateException) {
            // The device locked since, see above: the next sync, which waits for the unlock,
            // rebuilds it.
        }

        // A client that does not open leaves the state as a failed deletion does, see above.
        held.mlsGroupId?.let { mlsGroupId ->
            openedClient()?.deleteGroupQuietly(mlsGroupId = mlsGroupId)
        }

        // Best effort, see above: only a log-out before the server hears the leave needs it gone.
        deleteKeyCopy(held.uuid)

        when (sendLeave(held.uuid)) {
            GroupLeaveAnswer.Left -> forgetOwedLeave(held.uuid)

            // The 404 too, which may be the proxy's, the user still a member, see above.
            GroupLeaveAnswer.NotMember, is GroupLeaveAnswer.Failed ->
                resendScheduler.resendAfter(seconds = UNANSWERED_LEAVE_RESEND_SECONDS)
        }

        return Result.Success(Unit)
    }

    /**
     * The user's MLS client, or null when it does not open, the device having locked before this
     * process first opened it included: its identity is read with keys a locked device cannot use,
     * and reading them throws an [IllegalStateException] then. Called once the leave is recorded,
     * which is not cancellable, so the exception is never the caller going away, a
     * CancellationException being an IllegalStateException.
     */
    private suspend fun openedClient(): MlsClient? =
        try {
            (client() as? Result.Success)?.data
        } catch (_: IllegalStateException) {
            null
        }
}
