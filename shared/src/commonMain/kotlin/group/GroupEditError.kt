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
     * A change to the group is waiting on the server: its creation, which an edit would find no
     * group on the server to be made to, or a change to its members. The key rotation after a
     * removal seals the version the server held as it was built, see [GroupMembershipSender], and
     * taken after an edit it would put that version back, under the edit's revision.
     */
    data object ChangePending : GroupEditError

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
