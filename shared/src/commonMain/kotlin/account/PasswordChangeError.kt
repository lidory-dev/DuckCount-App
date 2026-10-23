package account

import util.Error
import util.NetworkError

/**
 * Why the password could not be changed. The password is still the one it was in every case but
 * for [Unconfirmed], and the session on this device is still there, unless the server turned it
 * down meanwhile: that ends it, and restarts the app telling the user, as for any expiry, see
 * `DuckCountClient.expireSession`.
 */
sealed interface PasswordChangeError : Error {

    /**
     * The current password is not the account's. OPAQUE turns it down on this device, before
     * anything is sent that would change it.
     */
    data object WrongPassword : PasswordChangeError

    /**
     * The new password turns up in a known breach, see
     * `networking.HaveIBeenPwnedClient.passwordLeakCount`. Our server is told nothing.
     */
    data object Leaked : PasswordChangeError

    /**
     * This device holds no root key to seal under the new password, see
     * [authenticationUtils.getRootKey]. Nothing is sent.
     */
    data object MissingRootKey : PasswordChangeError

    /**
     * The handshakes could not be run on this device for any other reason: the server's half of
     * them does not read as one, or the library refused them.
     */
    data object Crypto : PasswordChangeError

    /**
     * The server could not be reached, or refused the call: the change was not sent, was
     * refused, or failed with the session still taken after it, which the change would have
     * retired, see `PasswordChange.settle`.
     */
    data class Network(val error: NetworkError) : PasswordChangeError

    /**
     * The change went out and failed for [error], which leaves open that it went through, and
     * what became of it could not be found out after. Changed, the session this device holds is
     * one the server no longer takes, which the next refresh finds, ending it as an expired one.
     */
    data class Unconfirmed(val error: NetworkError) : PasswordChangeError
}
