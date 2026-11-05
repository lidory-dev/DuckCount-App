package vault

import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.EncryptedField
import uniffi.lib_duckcount.EncryptionException
import uniffi.lib_duckcount.decryptUsingSymmetricKey
import uniffi.lib_duckcount.decryptUsingSymmetricKeyWithAssociatedData
import uniffi.lib_duckcount.encryptUsingSymmetricKey
import uniffi.lib_duckcount.encryptUsingSymmetricKeyWithAssociatedData
import kotlin.io.encoding.Base64

/**
 * Seals [plaintext] under [key], or returns null when the key is the wrong size.
 */
internal fun seal(key: ByteArray, plaintext: ByteArray): EncryptedFieldDto? =
    try {
        encryptUsingSymmetricKey(key = key, plaintextMessage = plaintext).toDto()
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
            ciphertext = Base64.Default.decode(content.ciphertext),
            nonce = Base64.Default.decode(content.nonce),
        )
    } catch (_: EncryptionException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Seals [plaintext] under [key], bound to [context].
 *
 * AES-GCM authenticates [context] as associated data along with what it encrypts, so what
 * was sealed under one context only opens under that same context.
 *
 * Without it, everything sealed under one key is interchangeable with everything else
 * sealed under that key. Two beneficiaries' shares of one expense open with the same
 * key, so swapping the two stored blobs yields two perfectly valid decryptions and a
 * different answer to who owes what, with nothing anywhere recording that it happened.
 * The context is what makes a share that was sealed for one person fail to open as
 * another's.
 *
 * Returns null when the key is the wrong size.
 */
internal fun sealBound(key: ByteArray, context: String, plaintext: ByteArray): EncryptedFieldDto? =
    try {
        encryptUsingSymmetricKeyWithAssociatedData(
            key = key,
            plaintextMessage = plaintext,
            associatedData = context.encodeToByteArray(),
        ).toDto()
    } catch (_: EncryptionException) {
        null
    }

/**
 * This with as many spaces after it as make it [size] bytes, when it is not so long already, for
 * what is sealed: AES-GCM does not hide how long what it seals is.
 */
internal fun ByteArray.paddedTo(size: Int): ByteArray =
    this + ByteArray((size - this.size).coerceAtLeast(0)) { ' '.code.toByte() }

/**
 * Unseals [content] with [key] and returns what was sealed under [context], or null when
 * it was sealed under another context, with another key, has been tampered with, or is
 * not the Base64 the server is supposed to hold.
 *
 * All of those are the same answer to a caller: the bytes are not the ones it asked for
 * and there is nothing it can do about it.
 */
internal fun unsealBound(key: ByteArray, context: String, content: EncryptedFieldDto): ByteArray? =
    try {
        decryptUsingSymmetricKeyWithAssociatedData(
            key = key,
            ciphertext = Base64.Default.decode(content.ciphertext),
            nonce = Base64.Default.decode(content.nonce),
            associatedData = context.encodeToByteArray(),
        )
    } catch (_: EncryptionException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun EncryptedField.toDto() =
    EncryptedFieldDto(
        ciphertext = Base64.Default.encode(ciphertext()),
        nonce = Base64.Default.encode(nonce()),
    )
