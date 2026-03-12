package di

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeMemoryPolicy
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val sharedModule = module {
    single(named("prefs")) {
        KSafe(
            fileName = "prefs",
            memoryPolicy = KSafeMemoryPolicy.PLAIN_TEXT
        )
    }

    single(named("vault")) {
        KSafe(
            fileName = "vault"
        )
    }
}
