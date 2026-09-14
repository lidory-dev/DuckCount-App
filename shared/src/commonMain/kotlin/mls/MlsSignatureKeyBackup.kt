package mls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

private val json = Json { ignoreUnknownKeys = true }

/**
 * The plaintext of `VaultEntryKey.MLS_SIGNATURE_KEY`: the user's MLS signature key
 * pair, which is their MLS identity.
 *
 * Both halves are kept because restoring the client takes both, and the library offers
 * no way to derive the public key from the private one.
 */
class MlsSignatureKeyBackup(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {

    /** The bytes to seal into the vault. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedSignatureKey.serializer(),
            SerializedSignatureKey(
                privateKey = Base64.Default.encode(privateKey),
                publicKey = Base64.Default.encode(publicKey),
            ),
        ).encodeToByteArray()

    companion object {
        /**
         * Reads a backup back from the vault's plaintext, or returns null when those
         * bytes are not one.
         */
        fun parse(plaintext: ByteArray): MlsSignatureKeyBackup? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedSignatureKey.serializer(),
                    plaintext.decodeToString(),
                )

                MlsSignatureKeyBackup(
                    privateKey = Base64.Default.decode(serialized.privateKey),
                    publicKey = Base64.Default.decode(serialized.publicKey),
                )
            }.getOrNull()
    }
}

@Serializable
private class SerializedSignatureKey(
    @SerialName("private_key")
    val privateKey: String,

    @SerialName("public_key")
    val publicKey: String,
)
