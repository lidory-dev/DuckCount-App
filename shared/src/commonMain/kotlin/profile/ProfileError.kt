package profile

import util.Error
import util.NetworkError
import vault.VaultError

/** Why a user profile could not be uploaded or restored, or its key rotated. */
sealed interface ProfileError : Error {

    /**
     * The profile encryption key could not be reached, so nothing can be sealed, or the new
     * one could not be written in its place.
     */
    data class Vault(val error: VaultError) : ProfileError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : ProfileError

    /**
     * The server holds no profile for this user under this key: none was ever stored under
     * it, or the version stored under it has been pruned since.
     */
    data object NotStored : ProfileError

    /** A stored field could not be unsealed with the key that should open it, or was not padded as sealing pads it. */
    data object Crypto : ProfileError

    /**
     * The full name is longer than the server takes one sealed, see
     * [app.lidory.duckcount.ProfileLimits.MAX_FULL_NAME_LENGTH], and was refused before anything
     * was sealed.
     */
    data object FullNameTooLong : ProfileError
}
