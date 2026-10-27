package group

import contact.ContactSyncError
import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/**
 * What kept a [GroupSync] from doing all it had to. Whatever it did not get to stays where it was,
 * on the server or on this device, for the next sync.
 */
sealed interface GroupSyncError : Error {

    /** Nobody is signed in, so there are no groups to sync. */
    data object NoSession : GroupSyncError

    /**
     * The user's contacts could not be brought back from the vault. Until they are, every one of
     * them looks like a stranger, whose groups would be declined.
     */
    data class Contacts(val error: ContactSyncError) : GroupSyncError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupSyncError

    /** The vault could not be read, or would not take a group's key. */
    data class Vault(val error: VaultError) : GroupSyncError

    /** The user's own storage failed, so the same Welcome, or message, can be tried again later. */
    data object Storage : GroupSyncError

    /**
     * A Commit from the creator of a group seats a member under an identity other than the one
     * this device pinned for them, see [uniffi.lib_duckcount.MlsException.IdentityKeyMismatch]. It
     * is left waiting, and the group can go no further than the epoch before it, every later
     * message waiting behind it, until which identity is theirs is settled.
     *
     * Names nobody, as this is logged: who is in which of the user's groups is nobody else's
     * business.
     */
    data object IdentityChanged : GroupSyncError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : GroupSyncError
}
