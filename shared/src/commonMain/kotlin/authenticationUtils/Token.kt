package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

abstract class Token {
    companion object {
        const val SHORT_LIVED_TOKEN = "shortLivedToken"

        const val LONG_LIVED_TOKEN = "longLivedToken"
    }
}

/**
 * Stores a pair the server issued, and returns once both are on disk.
 *
 * Awaited, unlike the rest of the vault's writes: the server stops taking the previous
 * long-lived token the moment it issues a new one, so one this throws for is one the caller has
 * to keep hold of itself. A locked device is the usual reason, with an [IllegalStateException].
 *
 * The long-lived token goes first. Stored alone, it still gets the next refresh through, where
 * a short-lived one stored alone would leave the vault on a long-lived token the server turned
 * down.
 */
suspend fun setTokens(shortLivedToken: String, longLivedToken: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = Token.LONG_LIVED_TOKEN,
        value = longLivedToken,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )

    vault.put(
        key = Token.SHORT_LIVED_TOKEN,
        value = shortLivedToken,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearTokens() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = Token.SHORT_LIVED_TOKEN,
    )

    vault.deleteDirect(
        key = Token.LONG_LIVED_TOKEN,
    )
}

suspend fun getShortLivedToken(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get(Token.SHORT_LIVED_TOKEN, defaultValue = null)
}

suspend fun getLongLivedToken(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get(Token.LONG_LIVED_TOKEN, defaultValue = null)
}
