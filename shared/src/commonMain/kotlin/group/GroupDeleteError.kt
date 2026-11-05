package group

import util.Error
import util.NetworkError

/**
 * Why the user could not delete a group, see [GroupDeleter]. None of them leaves anything written
 * on this device: the group is as it was, and the same deletion can be asked for again.
 */
sealed interface GroupDeleteError : Error {

    /** Nobody is signed in, so there is nobody to delete the group as. */
    data object NoSession : GroupDeleteError

    /**
     * The device locked before anything was sent, as the deletion waited for a sync under way, say:
     * who is signed in is read with a key a locked device cannot use, see
     * [GroupSyncError.DeviceLocked].
     */
    data object DeviceLocked : GroupDeleteError

    /** This device holds no such group: a creation the server refused took it away, say. */
    data object NotHeld : GroupDeleteError

    /**
     * The user may not delete it, as it stands now: they are no longer in it, a sync having just
     * read their removal or found the group gone, or they did not create it, see
     * [database.data.local.entity.GroupEntity.ownerUuid].
     */
    data object NotOwner : GroupDeleteError

    /**
     * A change to the group waits on the server, its creation or a change to its members, as for an
     * edit, see [GroupEditError.ChangePending]: the group may not be on the server yet, and its
     * creation sent again after a deletion would bring it back.
     */
    data object ChangePending : GroupDeleteError

    /**
     * The server did not take the deletion, or was not heard to, [error]: only asking again can
     * tell, and a deletion it did take that this device did not hear is found by the next sync,
     * see [GroupSync].
     */
    data class Network(val error: NetworkError) : GroupDeleteError

    /**
     * The server took the deletion, and the user's own storage failed to forget the group: the next
     * sync finds it gone, and it stays on this device read-only as it does for its members.
     */
    data object Storage : GroupDeleteError
}
