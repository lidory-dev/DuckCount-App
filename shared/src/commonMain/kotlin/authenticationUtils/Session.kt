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
 *
 * Dropping the push subscription here only forgets what this device registered. Telling the
 * server to forget it too is `PushRegistrar.tearDown`, which has to run before this does, while
 * the tokens that call needs still exist.
 */
fun clearSession() {
    clearTokens()
    clearAuthenticatedUserUuid()
    clearAuthenticatedUserUsername()
    clearDiscoveryUsername()
    clearRegisteredPushSubscription()
    clearContactsToRestore()
    clearKeyPackagesPublishedAt()
    clearProfileKeyPartialHash()
    clearPendingProfileKey()
    clearRootKey()
    clearAccountDeletionInDoubt()
}

/**
 * Announces that the server no longer accepts this device's session, so the UI can take the user
 * back to the authentication flow instead of leaving them on a screen that can no longer load
 * anything, and tell them why, see [SessionEnd].
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
    private val _end = MutableStateFlow<SessionEnd?>(null)

    /** Why the server stopped taking the session, null until it does. */
    val end: StateFlow<SessionEnd?> = _end.asStateFlow()

    fun signal(end: SessionEnd) {
        _end.value = end
    }
}

/** Why the server turned this device's session down, as far as this device can tell. */
enum class SessionEnd {

    /**
     * It ran out, a login on another device replaced it, or the account went with nothing on this
     * device to say so, see [ACCOUNT_DELETED].
     */
    EXPIRED,

    /**
     * The account went with it: this device sent a deletion whose answer never came back, see
     * [setAccountDeletionInDoubt]. Nothing else that ends a session tells it from [EXPIRED].
     */
    ACCOUNT_DELETED,
}
