package authentication

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import kotlin.coroutines.resume

/**
 * The device's word for what it is, over a registration's challenge, which the server asks of an
 * iPhone before it says anything about a username: Apple's App Attest, the counterpart of the key
 * attestation an Android phone makes.
 *
 * The Secure Enclave generates a key, and Apple certifies it for this app with a nonce derived
 * from the SHA-256 of the challenge, which is what the server checks. A new key every time, as on
 * Android: App Attest attests a key once, and the server keeps none, no assertion coming later.
 */
class AppAttester {

    /** Attests a new key over [challenge], the registration challenge's bytes as decoded. */
    suspend fun attest(challenge: ByteArray): Attestation {
        val service = DCAppAttestService.sharedService

        // The Simulator, which has no Secure Enclave to generate a key in.
        if (!service.isSupported()) return Attestation.Unsupported

        val keyId = suspendCancellableCoroutine<String?> { continuation ->
            service.generateKeyWithCompletionHandler { keyId, error ->
                error?.let { println("$TAG: could not generate a key: ${it.localizedDescription}") }

                continuation.resume(keyId)
            }
        } ?: return Attestation.Failed

        val attestationObject = suspendCancellableCoroutine<NSData?> { continuation ->
            service.attestKey(keyId, clientDataHash = sha256(challenge).toNSData()) { attestationObject, error ->
                error?.let { println("$TAG: could not attest the key: ${it.localizedDescription}") }

                continuation.resume(attestationObject)
            }
        } ?: return Attestation.Failed

        return Attestation.Attested(attestationObject = attestationObject.base64EncodedStringWithOptions(0u))
    }

    private companion object {
        const val TAG = "AppAttester"
    }
}

/** What [AppAttester.attest] made of a challenge. */
sealed interface Attestation {

    /** The CBOR from `DCAppAttestService.attestKey`, in standard Base64, as the server takes it. */
    data class Attested(val attestationObject: String) : Attestation

    /** The device has no App Attest: the Simulator. */
    data object Unsupported : Attestation

    /** App Attest failed, Apple's servers being out of reach for instance, as trying again may change. */
    data object Failed : Attestation
}

@OptIn(ExperimentalForeignApi::class)
private fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)

    digest.usePinned { output ->
        bytes.usePinned { input ->
            CC_SHA256(
                // An empty array has no first byte to point at, and nothing to read either.
                if (bytes.isEmpty()) null else input.addressOf(0),
                bytes.size.convert(),
                output.addressOf(0).reinterpret(),
            )
        }
    }

    return digest
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}
