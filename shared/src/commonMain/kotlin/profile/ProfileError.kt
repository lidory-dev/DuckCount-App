package profile

import util.Error
import util.NetworkError
import vault.VaultError

/** Why a user profile could not be uploaded or restored. */
sealed interface ProfileError : Error {

    /** The profile encryption key could not be reached, so nothing can be sealed. */
    data class Vault(val error: VaultError) : ProfileError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : ProfileError

    /**
     * The server holds no profile for this user under this key.
     */
    data object NotStored : ProfileError

    /** A stored field could not be unsealed with the key that should open it. */
    data object Crypto : ProfileError
}
