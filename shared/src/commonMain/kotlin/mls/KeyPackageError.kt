package mls

import util.Error
import util.NetworkError

/** Why the user's key packages could not be published. */
sealed interface KeyPackageError : Error {

    /** The MLS client the key packages come from could not be opened. */
    data class Identity(val error: MlsIdentityError) : KeyPackageError

    /** The library could not generate the key packages. */
    data object Crypto : KeyPackageError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : KeyPackageError
}
