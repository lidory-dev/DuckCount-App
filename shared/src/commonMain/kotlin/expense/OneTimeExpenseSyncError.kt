package expense

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why bringing the user's one-time expenses up to date stopped short.
 *
 * Nothing here is final: whatever did not get done is picked up by the next attempt.
 */
sealed interface OneTimeExpenseSyncError : Error {

    /** Nobody is signed in, so there is nothing to sync and nobody to sync it for. */
    data object NoSession : OneTimeExpenseSyncError

    /** The user's own MLS client could not be opened, so nothing could be sent. */
    data class Identity(val error: MlsIdentityError) : OneTimeExpenseSyncError

    /** The user's own storage failed. Nothing was consumed, so trying again may work. */
    data object Storage : OneTimeExpenseSyncError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : OneTimeExpenseSyncError
}
