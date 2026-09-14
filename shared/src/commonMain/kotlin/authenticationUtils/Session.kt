package authenticationUtils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wipes everything the vault holds about who is signed in.
 *
 * Shared by the deliberate log-out and by the forced one a rejected session triggers.
 *
 * The rest lives in `DuckCountClient.endSession`, which calls this: the bearer tokens the HTTP
 * client caches, which only the client can drop, and the account's data in the local database.
 */
fun clearSession() {
    clearTokens()
    clearAuthenticatedUserUuid()
    clearAuthenticatedUserUsername()
    clearDiscoveryUsername()
    clearRootKey()
}

/**
 * Announces that the server no longer accepts this device's session, so the UI can take the user
 * back to the authentication flow instead of leaving them on a screen that can no longer load
 * anything.
 *
 * Raised when the refresh (long-lived) token is rejected, which is the point of no return: the
 * short-lived token expiring on its own is refreshed transparently and never gets here.
 *
 * State rather than an event stream on purpose: a `401` can land before anything is collecting
 * (during startup, or while the activity is being recreated), and that must not be the one signal
 * that gets dropped. It is never reset: acting on it restarts the process, like every other end of
 * a session (see `DuckCountClient.endSession`).
 */
class SessionExpiry {
    private val _isExpired = MutableStateFlow(false)

    val isExpired: StateFlow<Boolean> = _isExpired.asStateFlow()

    fun signal() {
        _isExpired.value = true
    }
}
