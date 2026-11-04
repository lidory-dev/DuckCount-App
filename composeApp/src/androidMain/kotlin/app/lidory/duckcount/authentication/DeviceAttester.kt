package app.lidory.duckcount.authentication

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import kotlin.uuid.Uuid

private const val TAG = "DeviceAttester"

private const val ANDROID_KEY_STORE = "AndroidKeyStore"

private const val P_256 = "secp256r1"

/**
 * Proves to the server that this is a real phone, which it asks at the very start of a registration,
 * before it says anything about the username.
 */
interface DeviceAttester {

    /**
     * The certificate chain of a new key attested with [challenge], leaf first, each certificate
     * in standard Base64 DER, or null when this phone could not produce one.
     *
     * Only the server judges the chain, in a debug build as in any other: an emulator hands over
     * the software chain it produces, which it takes only when its own configuration says so.
     */
    suspend fun attest(challenge: ByteArray): List<String>?
}

/**
 * Attests with a key in the Android Keystore, generated for this one chain and deleted with it:
 * the server reads what the certificates say and never asks for anything to be signed, so the key
 * is never used, and nothing is asked of it beyond what generating one takes. No StrongBox, which
 * not every phone has and the server does not ask for, and no device IDs, which would only tell
 * it more about the phone.
 */
class KeystoreDeviceAttester : DeviceAttester {

    // Off the main thread: generating the key is slow, the secure hardware doing it.
    override suspend fun attest(challenge: ByteArray): List<String>? = withContext(Dispatchers.Default) {
        // A fresh alias each time, so that a key left behind by a process killed before it could
        // delete it is never in the way.
        val alias = "registration-attestation-${Uuid.random()}"

        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

            try {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
                    initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec(P_256))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setAttestationChallenge(challenge)
                            .build(),
                    )
                    generateKeyPair()
                }

                // Null too when the keystore fails on the way, rather than throwing.
                keyStore.getCertificateChain(alias)?.map { it.encoded.toBase64() }
            } finally {
                // The chain is as good either way, and the alias is never used again.
                try {
                    keyStore.deleteEntry(alias)
                } catch (exception: Exception) {
                    Log.w(TAG, "could not delete the attested key", exception)
                }
            }
        } catch (exception: Exception) {
            // Checked or not, as the framework does not stop at what it declares when the keystore
            // daemon is gone. Nothing in here suspends, so this is never a cancellation.
            Log.w(TAG, "could not attest", exception)

            null
        }
    }
}
