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
 * AES-GCM authenticates what it encrypts and nothing else, and `lib-duckcount` exposes
 * no way to pass it associated data, so the binding goes inside the plaintext: what
 * comes back out has to name the slot it was meant for, or it is refused.
 *
 * Without it, everything sealed under one key is interchangeable with everything else
 * sealed under that key. Two beneficiaries' shares of one expense open with the same
 * key, so swapping the two stored blobs yields two perfectly valid decryptions and a
 * different answer to who owes what, with nothing anywhere recording that it happened.
 * The context is what makes a share that was sealed for one person fail to open as
 * another's.
 *
 * The bound form is `context length (2 bytes, big endian) ‖ context ‖ plaintext`.
 * Returns null when the key is the wrong size, or when [context] is too long to
 * describe, which no caller-supplied context is.
 */
internal fun sealBound(key: ByteArray, context: String, plaintext: ByteArray): EncryptedFieldDto? {
    val bound = bind(context = context, plaintext = plaintext) ?: return null

    return seal(key = key, plaintext = bound)
}

/**
 * Unseals [content] with [key] and returns what was sealed under [context], or null when
 * it was sealed under another context, with another key, has been tampered with, or is
 * not the Base64 the server is supposed to hold.
 *
 * All of those are the same answer to a caller: the bytes are not the ones it asked for
 * and there is nothing it can do about it.
 */
internal fun unsealBound(key: ByteArray, context: String, content: EncryptedFieldDto): ByteArray? {
    val bound = unseal(key = key, content = content) ?: return null

    return unbind(context = context, bound = bound)
}

/** The bound plaintext [sealBound] seals, or null when [context] is too long to prefix. */
internal fun bind(context: String, plaintext: ByteArray): ByteArray? {
    val contextBytes = context.encodeToByteArray()

    if (contextBytes.size > MAX_CONTEXT_LENGTH) return null

    val bound = ByteArray(CONTEXT_LENGTH_SIZE + contextBytes.size + plaintext.size)

    bound[0] = (contextBytes.size ushr Byte.SIZE_BITS).toByte()
    bound[1] = contextBytes.size.toByte()
    contextBytes.copyInto(bound, destinationOffset = CONTEXT_LENGTH_SIZE)
    plaintext.copyInto(bound, destinationOffset = CONTEXT_LENGTH_SIZE + contextBytes.size)

    return bound
}

/**
 * What [bind] put under [context], or null when [bound] was bound to another context or
 * is too short to carry one.
 *
 * The prefix is compared whole rather than only its length, so a context that is another
 * one's prefix, `share` against `share:$uuid`, cannot pass for it.
 */
internal fun unbind(context: String, bound: ByteArray): ByteArray? {
    val expected = bind(context = context, plaintext = ByteArray(0)) ?: return null

    if (bound.size < expected.size) return null

    expected.indices.forEach { index ->
        if (bound[index] != expected[index]) return null
    }

    return bound.copyOfRange(expected.size, bound.size)
}

/** How many bytes the length prefix of a context takes. */
private const val CONTEXT_LENGTH_SIZE = 2

/** The longest context that prefix can describe. */
private const val MAX_CONTEXT_LENGTH = 0xFFFF
