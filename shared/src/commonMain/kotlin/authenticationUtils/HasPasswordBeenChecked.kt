package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val HAS_PASSWORD_BEEN_CHECKED = "hasPasswordBeenChecked"

fun setHasPasswordBeenChecked(hasPasswordBeenChecked: Boolean) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = HAS_PASSWORD_BEEN_CHECKED,
        value = hasPasswordBeenChecked,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}
