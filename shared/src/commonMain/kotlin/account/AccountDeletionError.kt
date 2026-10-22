package account

import util.Error
import util.NetworkError

/**
 * Why the account could not be deleted. The session on this device is still there in every
 * case, unless the server turned it down meanwhile: the refresh that finds that ends it, and
 * restarts the app telling the user, as for any expiry, see `DuckCountClient.expireSession`.
 *
 * So is the account, as far as the attempt could tell, but for [Unconfirmed]. It may still be
 * gone after a deletion an earlier attempt sent and never heard back about: the attempt after
 * finds that out, see [AccountDeletion.delete], and so does the server turning the session down
 * later, see [authenticationUtils.SessionEnd.ACCOUNT_DELETED].
 */
sealed interface AccountDeletionError : Error {

    /**
     * The password is not the account's. OPAQUE turns it down on this device, before anything
     * is sent that would delete the account or tell anybody.
     */
    data object WrongPassword : AccountDeletionError

    /**
     * The re-authentication could not be run on this device for any other reason: the server's
     * half of it does not read as one, or the library refused it.
     */
    data object Crypto : AccountDeletionError

    /**
     * The server could not be reached, or refused the call: the deletion was not sent, was
     * refused, or failed with the session still taken after it, see
     * `DuckCountClient.isSessionAccepted`.
     */
    data class Network(val error: NetworkError) : AccountDeletionError

    /**
     * A deletion went out, and the server could not be asked after whether it went through:
     * this attempt's, which failed for [error], leaving that open, or an earlier attempt's still
     * in doubt, the server having answered this one's re-authentication with [error], a `404`,
     * as it does for an account that is gone. Trying again finds out.
     */
    data class Unconfirmed(val error: NetworkError) : AccountDeletionError
}
