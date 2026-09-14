package mls

import util.Error
import vault.VaultError

/** Why the user's MLS client could not be opened. */
sealed interface MlsIdentityError : Error {

    /**
     * Nobody is signed in on this device, or their root key is gone, so there is no
     * identity to open.
     */
    data object MissingSession : MlsIdentityError

    /** The identity backup could not be read from or written to the vault. */
    data class Vault(val error: VaultError) : MlsIdentityError

    /**
     * The library refused to open the client, or the vault holds a backup that is not a
     * key pair it can restore.
     */
    data object Crypto : MlsIdentityError
}
