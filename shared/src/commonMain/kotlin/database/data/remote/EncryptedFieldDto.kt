package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `EncryptedField` JSON object (`{ ciphertext, nonce }`), in Base64.
 *
 * Always real AES-GCM output: the vault root key, at registration and on a password
 * change, every vault entry the key ring seals, a one-time expense's fields under its
 * own key, and a group's title, description and currency under the group's key, see
 * `group.GroupSealing`, each of the last two bound to the field it is for.
 */
@Serializable
data class EncryptedFieldDto(
    val ciphertext: String,
    val nonce: String,
)
