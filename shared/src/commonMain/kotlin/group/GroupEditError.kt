package group

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why what the user edited of a group was not saved, see [GroupEditor]. None but [Outdated] leaves
 * anything written on this device: the group shows what it showed, and the same edit can be made
 * again. After [Outdated], it shows the newer version the server holds, which the edit can be made
 * again over.
 */
sealed interface GroupEditError : Error {

    /** Nobody is signed in, so there is nobody to edit the group as. */
    data object NoSession : GroupEditError

    /**
     * The device locked before the edit could be made, as it waited for a sync under way, say: who
     * is signed in and the user's MLS identity, until it is opened in this process, are read with
     * keys a locked device cannot use, see [GroupSyncError.DeviceLocked].
     */
    data object DeviceLocked : GroupEditError

    /** This device holds no such group: a creation the server refused took it away, say. */
    data object NotHeld : GroupEditError

    /**
     * The user did not create the group, as this device records it, see
     * [database.data.local.entity.GroupEntity.ownerUuid], and its members show no version of its
     * information but those its creator signed.
     */
    data object NotCreator : GroupEditError

    /**
     * The title and description are those the group shows already, see
     * [database.data.local.entity.GroupEntity.title]: an edit of them would change nothing, and
     * still wake every other member and take a place in the group's history, see [GroupHistory],
     * pushing the oldest version out of it.
     */
    data object Unchanged : GroupEditError

    /**
     * A change to the group is waiting on the server: its creation, which an edit would find no
     * group on the server to be made to, or a Commit of the user's, which takes the group to the
     * epoch the edit would have to be sealed in.
     */
    data object ChangePending : GroupEditError

    /**
     * The server takes the group's information under a newer generation of its key than this device
     * holds, and syncing the group did not bring it: a Commit this device could not read yet, or
     * one it refused, which the user's device replaces once it can, see [GroupSync], or a group
     * brought back after a log-out, which follows no epoch, see [GroupKeyChainError.NotHeld].
     * Nothing was written.
     */
    data object KeyNotCurrent : GroupEditError

    /**
     * The server holds a version newer than the one this device edited, see
     * [EditGroupBody.revision]: an earlier edit of the user's whose answer was lost, the creator
     * being the one member who edits, with another title or description than this one, which
     * would otherwise be this very edit, made already, see [GroupEditor]. This device shows it
     * once it is read, and the edit can be made again over it.
     */
    data object Outdated : GroupEditError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupEditError

    /** The library could not sign the version, or it could not be sealed. */
    data object Crypto : GroupEditError

    /**
     * The server could not be reached, or refused the edit. An edit is not sent again: one the
     * server took whose answer was lost is shown once the next sync lists it, see [GroupSync].
     */
    data class Network(val error: NetworkError) : GroupEditError
}
