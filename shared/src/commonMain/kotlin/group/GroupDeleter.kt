package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import push.WakeList
import uniffi.lib_duckcount.MlsClient
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Deletes a group the user created for everybody, as they ask from its settings: on the server
 * first, then on this device, which forgets it. It is the way out of a group its creator may not
 * leave, see [GroupLeaver]. Deleting their account deletes it too, which the members' devices find
 * the same way, see [GroupSync].
 *
 * The server first, unlike a leave, see [GroupLeaver], which is the user's own business and goes out
 * whenever the network lets it: this one takes the group from everybody, so it is made while the
 * user is there to hear that it was, and never sent later on its own. Without an answer nothing
 * changes here, and the user asks again.
 *
 * In this order, under [withGroupsLock] as a sync and a change to a group's members are. The lock
 * is not reentrant, so this is never called from under it: the screens call it, the sync never does.
 * 1. The group is read again, as it stands now. One the user is no longer in, or does not own, is
 *    refused, see [GroupDeleteError.NotOwner], and so is one a change waits on the server in, see
 *    [GroupDeleteError.ChangePending]: its creation sent again after the deletion would bring the
 *    group back, and a change to its members is sent by the next sync, which the settings wait for.
 * 2. The deletion is sent, see [GroupApi.delete]. A `404` is taken for the group being gone only
 *    once the server lists it no more, see [GroupApi.getGroups]: an earlier request it took, whose
 *    answer was lost, has nothing else to say that it did, and the proxy in front of the server
 *    answers one too as it is redeployed, the group still there. Anything else is no answer.
 * 3. The group is forgotten, everything this device kept of it going with it, see
 *    [GroupDao.forgetDeleted], and who may wake the user rebuilt, see [WakeList]: its members no
 *    longer do, but for those in another of their groups, and their contacts.
 * 4. Its MLS state is deleted, when this device holds it, once the rows no longer name it, as for a
 *    creation the server refused, see [GroupDao.deleteRefusedCreation].
 *
 * From the request to the end is not cancellable, so that the screen going away does not leave a
 * group the server no longer holds to look like one the user is still in.
 *
 * Every member's device finds the group gone at its next sync, which the server wakes them for, and
 * keeps what it had of it read-only, see [GroupSync]. The owner's keeps nothing, having asked. One
 * that did not hear the answer, the device locking or the process going away, finds it gone the same
 * way and keeps its copy too, nothing saying it asked: a sync never takes anything off this device
 * on the server's word alone.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] is [GroupDao.get], [hasPendingChange] is
 * [GroupDao.hasPendingChange], [sendDelete] and [listGroups] are [GroupApi.delete] and
 * [GroupApi.getGroups], [forget] is [GroupDao.forgetDeleted], [rebuildWakeList] is [WakeList.rebuild]
 * and [client] is [MlsIdentityRepository.client], or what a test stands in for any of them.
 */
class GroupDeleter internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val sendDelete: suspend (groupUuid: String) -> EmptyResult<NetworkError>,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val forget: suspend (groupUuid: String) -> Unit,
    private val rebuildWakeList: suspend () -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        wakeList: WakeList,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        group = groupDao::get,
        hasPendingChange = groupDao::hasPendingChange,
        sendDelete = groupApi::delete,
        listGroups = groupApi::getGroups,
        forget = groupDao::forgetDeleted,
        rebuildWakeList = wakeList::rebuild,
        client = mlsIdentityRepository::client,
    )

    /** Deletes the group [groupUuid] for everybody, see [GroupDeleter]. */
    suspend fun delete(groupUuid: String): Result<Unit, GroupDeleteError> = withGroupsLock {
        // The device may have locked as this waited for the lock, see GroupLeaver.
        val selfUuid = try {
            userUuid()
        } catch (_: IllegalStateException) {
            return@withGroupsLock Result.Error(GroupDeleteError.DeviceLocked)
        } ?: return@withGroupsLock Result.Error(GroupDeleteError.NoSession)

        val held = group(groupUuid) ?: return@withGroupsLock Result.Error(GroupDeleteError.NotHeld)

        if (held.isLeft || held.ownerUuid != selfUuid) {
            return@withGroupsLock Result.Error(GroupDeleteError.NotOwner)
        }

        if (hasPendingChange(groupUuid)) return@withGroupsLock Result.Error(GroupDeleteError.ChangePending)

        withContext(NonCancellable) { deleteHeld(held) }
    }

    /** Deletes [held], a group the user owns with no change waiting, from step 2 on, see [GroupDeleter]. */
    private suspend fun deleteHeld(held: GroupEntity): Result<Unit, GroupDeleteError> {
        val sent = sendDelete(held.uuid)

        if (sent is Result.Error && !(sent.error == NetworkError.NOT_FOUND && isGone(held.uuid))) {
            return Result.Error(GroupDeleteError.Network(sent.error))
        }

        try {
            forget(held.uuid)
        } catch (_: Exception) {
            return Result.Error(GroupDeleteError.Storage)
        }

        // Its members wake the user no longer, see above.
        try {
            rebuildWakeList()
        } catch (_: IllegalStateException) {
            // The device locked since, see GroupLeaver: the next sync, which waits for the unlock,
            // rebuilds it.
        }

        // A client that does not open leaves the state as a failed deletion does: nothing reads it,
        // the group gone from every list that would.
        held.mlsGroupId?.let { mlsGroupId ->
            openedClient()?.deleteGroupQuietly(mlsGroupId = mlsGroupId)
        }

        return Result.Success(Unit)
    }

    /** Whether the server answers and lists the group [groupUuid] no more, see [GroupDeleter]. */
    private suspend fun isGone(groupUuid: String): Boolean =
        (listGroups() as? Result.Success)?.data?.none { it.uuid == groupUuid } == true

    /**
     * The user's MLS client, or null when it does not open, the device having locked before this
     * process first opened it included, see [GroupLeaver]. Called once the group is forgotten, which
     * is not cancellable, so the exception is never the caller going away.
     */
    private suspend fun openedClient(): MlsClient? =
        try {
            (client() as? Result.Success)?.data
        } catch (_: IllegalStateException) {
            null
        }
}
