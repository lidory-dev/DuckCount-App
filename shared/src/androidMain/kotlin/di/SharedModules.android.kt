package di

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeMemoryPolicy
import org.koin.android.ext.koin.androidApplication
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import push.VaultWakeListStore
import push.WakeListStore

actual val sharedModule = module {
    single(named("prefs")) {
        KSafe(
            context = androidApplication(),
            fileName = "prefs",
            memoryPolicy = KSafeMemoryPolicy.PLAIN_TEXT,
        )
    }

    single(named("vault")) {
        KSafe(
            context = androidApplication(),
            fileName = "vault",
        )
    }

    // Only Android has push so far, so only Android keeps who may wake the user.
    single { VaultWakeListStore(get(named("vault"))) }.bind<WakeListStore>()
}
