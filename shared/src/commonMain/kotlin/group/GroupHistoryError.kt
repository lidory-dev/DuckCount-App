package group

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/** Why the history of a group's information could not be read, see [GroupHistory]. */
sealed interface GroupHistoryError : Error {

    /** Nobody is signed in, so there is nobody to read the history as. */
    data object NoSession : GroupHistoryError

    /**
     * The device locked before the history could be read: who is signed in and the user's MLS
     * identity, until it is opened in this process, are read with keys a locked device cannot
     * use, see [GroupSyncError.DeviceLocked], and the creator's key is read from that identity.
     */
    data object DeviceLocked : GroupHistoryError

    /**
     * This device holds no such group that the user is still in: a creation the server refused
     * took it away, say, or the user left it, or its creator removed them, and the server gives
     * its history to its current members alone.
     */
    data object NotMember : GroupHistoryError

    /**
     * None of the versions the server keeps opens under the key of its generation this device holds,
     * or gets back through the chain, see [GroupKeyChain], and was signed by its creator, see
     * [GroupHistory]: those made in an epoch this device has yet to reach, until its sync reads the
     * Commit leading there, see [GroupSync], or, for a group brought back after a log-out, ever. The
     * history reads as it should then.
     */
    data object Unreadable : GroupHistoryError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupHistoryError

    /** The user's own storage failed as the key the group's creator was pinned with was read. */
    data object Storage : GroupHistoryError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : GroupHistoryError
}
