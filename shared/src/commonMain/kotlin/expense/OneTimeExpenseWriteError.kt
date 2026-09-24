package expense

import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/**
 * Why writing a one-time expense did not go through.
 *
 * Only the failures *before* the expense reaches the server are reported to the user as a
 * failed write. Once it is on the server it exists, and the user retrying would create a
 * second one: each attempt draws a new nonce, so a retry is a new expense rather than the
 * same one again. Handing the keys out afterwards is left to the sync,
 * which is built to find and fix exactly that.
 */
sealed interface OneTimeExpenseWriteError : Error {

    /** Nobody is signed in. */
    data object NoSession : OneTimeExpenseWriteError

    /**
     * The creditor's copy of the expense key could not be sealed: the key it is sealed under
     * could not be read or created, or sealing under it failed.
     */
    data class Vault(val error: VaultError) : OneTimeExpenseWriteError

    /** The user's MLS identity, which signs the expense, could not be opened. */
    data class Identity(val error: MlsIdentityError) : OneTimeExpenseWriteError

    /**
     * The expense could not be given a UUID, sealed or signed: a session UUID that does not
     * parse, a key of the wrong size or a failed signature, all bugs rather than states.
     */
    data object Crypto : OneTimeExpenseWriteError

    /**
     * The expense is being edited but this device does not hold its key, or shows no version
     * of it to edit.
     *
     * Re-sealing under a new key would leave every participant holding one that no longer
     * opens it, and the amount would change underneath them, so the edit is refused instead.
     */
    data object KeyMissing : OneTimeExpenseWriteError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : OneTimeExpenseWriteError
}
