package group

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * What kept a [GroupExpenseSync] from bringing every group's expenses up to date. Nothing here is
 * final: whatever did not get done is picked up by the next pass.
 */
sealed interface GroupExpenseSyncError : Error {

    /** Nobody is signed in, so there are no groups to sync the expenses of. */
    data object NoSession : GroupExpenseSyncError

    /** The user's own MLS client, whose key and pins the versions are checked against, could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupExpenseSyncError

    /** The user's own storage failed as the key pinned for an author was read. */
    data object Storage : GroupExpenseSyncError

    /**
     * The server could not be reached, or refused the call: its `404` among them, for a group the
     * user is no longer a current member of, which the group sync reads next, see [GroupSync], and
     * whose expenses this device keeps as they were from then on.
     */
    data class Network(val error: NetworkError) : GroupExpenseSyncError
}
