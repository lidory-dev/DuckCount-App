package networking

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha1(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA1_DIGEST_LENGTH)

    digest.usePinned { output ->
        bytes.usePinned { input ->
            CC_SHA1(
                // An empty array has no first byte to point at, and nothing to read either.
                if (bytes.isEmpty()) null else input.addressOf(0),
                bytes.size.convert(),
                output.addressOf(0).reinterpret(),
            )
        }
    }

    return digest
}
