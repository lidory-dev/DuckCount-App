package vault

import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.EncryptionException
import uniffi.lib_duckcount.decryptUsingSymmetricKey
import uniffi.lib_duckcount.encryptUsingSymmetricKey
import kotlin.io.encoding.Base64

/**
 * Seals [plaintext] under [key], or returns null when the key is the wrong size.
 */
internal fun seal(key: ByteArray, plaintext: ByteArray): EncryptedFieldDto? =
    try {
        encryptUsingSymmetricKey(key = key, plaintextMessage = plaintext).let { sealed ->
            EncryptedFieldDto(
                ciphertext = Base64.Default.encode(sealed.ciphertext()),
                nonce = Base64.Default.encode(sealed.nonce()),
            )
        }
    } catch (_: EncryptionException) {
        null
    }

/**
 * Unseals [content] with [key], or returns null when it was sealed with another key,
 * has been tampered with, or is not the Base64 the server is supposed to hold.
 * AES-GCM reports the first two the same way, and none of the three is a state the
 * caller can do anything about.
 */
internal fun unseal(key: ByteArray, content: EncryptedFieldDto): ByteArray? =
    try {
        decryptUsingSymmetricKey(
            key = key,
            cipherText = Base64.Default.decode(content.ciphertext),
            nonce = Base64.Default.decode(content.nonce),
        )
    } catch (_: EncryptionException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
