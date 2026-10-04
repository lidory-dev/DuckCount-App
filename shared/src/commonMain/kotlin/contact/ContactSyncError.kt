package contact

import mls.MlsIdentityError
import profile.ProfileError
import util.Error
import util.NetworkError
import vault.VaultError

/**
 * Why bringing the user's contacts or contact requests up to date stopped short.
 *
 * Nothing here is final: whatever did not get done is picked up by the next attempt.
 */
sealed interface ContactSyncError : Error {

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : ContactSyncError

    /**
     * The user's own MLS storage failed. Nothing was consumed, so trying again later may
     * work.
     */
    data object Storage : ContactSyncError

    /** The contacts could not be read from or written to the vault. */
    data class Vault(val error: VaultError) : ContactSyncError

    /**
     * A contact's or a sender's profile could not be asked for.
     *
     * Never what the server answered: a profile it holds none of under the key this device
     * has, or one that does not open with that key, is its owner's to put right, and
     * failing on it would fail every sync until they did. Anybody can send the user a
     * request whose key opens nothing. The name is asked for again by the next sync either
     * way, since the owner can store their profile at any time.
     */
    data class Profile(val error: ProfileError) : ContactSyncError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : ContactSyncError
}
