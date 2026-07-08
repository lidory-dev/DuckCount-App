package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `EncryptedField` JSON object (`{ field, nonce }`).
 *
 * TODO: Encryption is not wired up yet: for now these carry placeholder
 * values so the server accepts the request. Replace with real AES-GCM output
 * once key management lands.
 */
@Serializable
data class EncryptedFieldDto(
    val field: String,
    val nonce: String,
)
