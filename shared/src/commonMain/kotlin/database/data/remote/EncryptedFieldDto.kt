package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `EncryptedField` JSON object (`{ ciphertext, nonce }`).
 *
 * Carries real AES-GCM output where key management has landed (the vault root
 * key at registration).
 *
 * TODO: Everywhere else encryption is not wired up yet, and these still carry
 * placeholder values so the server accepts the request. Replace with real
 * AES-GCM output as each part gains its key.
 */
@Serializable
data class EncryptedFieldDto(
    val ciphertext: String,
    val nonce: String,
)
