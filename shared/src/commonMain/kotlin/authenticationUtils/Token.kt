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

fun setTokens(shortLivedToken: String, longLivedToken: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = Token.SHORT_LIVED_TOKEN,
        value = shortLivedToken,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )

    vault.putDirect(
        key = Token.LONG_LIVED_TOKEN,
        value = longLivedToken,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
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
