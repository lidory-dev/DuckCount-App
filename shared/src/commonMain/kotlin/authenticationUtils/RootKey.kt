package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin
import kotlin.io.encoding.Base64

const val ROOT_KEY = "rootKey"

/**
 * Saves the vault root key, the one key every other key the user holds hangs off:
 * it seals the key ring, and the key ring seals every other part of the vault.
 *
 * The server only ever holds it sealed under a key derived from the password
 * (the OPAQUE export key), which it never sees. The client can only unseal it
 * during a registration or a login, so it has to be kept rather than re-derived:
 * [getLongLivedToken] resuming a session brings back no password to derive it
 * from.
 *
 * Stored Base64 encoded, since KSafe holds primitives rather than byte arrays.
 */
fun setRootKey(rootKey: ByteArray) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = ROOT_KEY,
        value = Base64.Default.encode(rootKey),
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.HARDWARE_ISOLATED,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearRootKey() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = ROOT_KEY,
    )
}

/**
 * The type argument is load-bearing and must not be dropped: with only
 * `defaultValue = null` to go on, and a `?.let` that swallows the expected type
 * (`Nothing` being a subtype of `String`, the lambda constrains nothing), Kotlin
 * infers `T = Nothing?` and KSafe deserializes the stored value into a null. The
 * key reads back as absent while sitting on disk perfectly intact.
 */
suspend fun getRootKey(): ByteArray? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<String?>(ROOT_KEY, defaultValue = null)?.let { encoded: String ->
        Base64.Default.decode(encoded)
    }
}
