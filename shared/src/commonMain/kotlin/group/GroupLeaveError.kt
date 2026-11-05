package group

import util.Error

/**
 * Why the user could not leave a group, see [GroupLeaver]. None of them leaves anything written:
 * the group is as it was, still current on this device and on the server, so the leave can be
 * asked for again.
 *
 * The server never is one: once this device records the leave, it is the user's whatever the
 * server answers, sent again until the server hears it.
 */
sealed interface GroupLeaveError : Error {

    /** Nobody is signed in, so there is nobody to leave the group as. */
    data object NoSession : GroupLeaveError

    /**
     * The device locked before the leave could be recorded, as it waited for a sync under way, say:
     * who is signed in is read with a key a locked device cannot use, see
     * [GroupSyncError.DeviceLocked].
     */
    data object DeviceLocked : GroupLeaveError

    /** This device holds no such group: a creation the server refused took it away, say. */
    data object NotHeld : GroupLeaveError

    /**
     * The user created the group, as this device records it, see
     * [database.data.local.entity.GroupEntity.ownerUuid], and its creator never leaves: nobody
     * else could add anybody to it, the members' devices taking Commits from the creator alone,
     * but for a removal of those who departed.
     * The server refuses its owner the leave anyway. Their way out is deleting it, see
     * [GroupDeleter].
     */
    data object IsCreator : GroupLeaveError

    /** The user's own storage failed to record the leave, so the same leave can be asked for again. */
    data object Storage : GroupLeaveError
}
