package discoveryUsername

import util.Error
import util.NetworkError
import vault.VaultError

/** Why a discovery username could not be reserved, or read back. */
sealed interface DiscoveryUsernameError : Error {

    /** The server refused the reservation, or could not be reached. */
    data class Reservation(val error: NetworkError) : DiscoveryUsernameError

    /** The handle could not be sealed into the vault, or unsealed out of it. */
    data class Vault(val error: VaultError) : DiscoveryUsernameError
}
