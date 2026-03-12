package di

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeMemoryPolicy
import org.koin.android.ext.koin.androidApplication
import org.koin.core.qualifier.named
import org.koin.dsl.module

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
}
