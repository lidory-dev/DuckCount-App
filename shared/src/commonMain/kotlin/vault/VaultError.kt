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
     * The vault holds a part whose key the key ring does not carry, so the part can
     * never be read again.
     */
    data object OrphanedEntry : VaultError

    /**
     * Another device has updated the entry, the key ring or the contacts, while this one
     * was trying to do the same.
     */
    data object Contended : VaultError

    /**
     * A collection part already holds as many items as the server allows, so nothing more
     * can be written to it until something is deleted.
     *
     * Terminal, unlike [Contended]: retrying writes the same item into the same full part
     * and fails the same way. The server reports both with a `409`, and they are told
     * apart by whether the write carried an expected version, which only a write built on
     * a previous read does.
     */
    data object PartFull : VaultError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : VaultError
}
