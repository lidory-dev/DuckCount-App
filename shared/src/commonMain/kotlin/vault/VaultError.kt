package vault

import util.Error
import util.NetworkError

/** Why a vault operation could not be carried out. */
sealed interface VaultError : Error {

    /**
     * This device holds no root key, so nothing in the vault can be opened.
     *
     * The root key is only ever unsealed during a registration or a login, so the way
     * out is to log in again.
     */
    data object MissingRootKey : VaultError

    /**
     * An entry could not be sealed or unsealed with the key that should open it.
     *
     * Either the key is not the one the entry was sealed with, or the stored bytes
     * have been tampered with (AES-GCM reports both the same way).
     */
    data object Crypto : VaultError

    /** The key ring's plaintext is not the map of part keys it is supposed to be. */
    data object MalformedKeyRing : VaultError

    /**
     * The contacts' plaintext is not the map of contacts it is supposed to be, so it is
     * left alone rather than overwritten with the contacts this build could make sense
     * of.
     */
    data object MalformedContactBook : VaultError

    /**
     * The plaintext of the members' keys is not the map of members it is supposed to be, see
     * [MemberProfileBook], so it is left alone rather than overwritten with the members this build
     * could make sense of.
     */
    data object MalformedMemberProfileBook : VaultError

    /**
     * The plaintext of the users the user blocked is not the map of them it is supposed to be, see
     * [BlockList], so it is left alone rather than overwritten with the users this build could make
     * sense of.
     */
    data object MalformedBlockList : VaultError

    /**
     * The vault holds a part whose key the key ring does not carry, so the part can
     * never be read again.
     */
    data object OrphanedEntry : VaultError

    /**
     * Another device has updated the entry, the key ring or the contacts, while this one
     * was trying to do the same.
     */
    data object Contended : VaultError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : VaultError
}
