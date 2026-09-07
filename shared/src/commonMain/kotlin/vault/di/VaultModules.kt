package vault.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import vault.VaultApi
import vault.VaultRepository

val vaultModule = module {
    singleOf(::VaultApi)
    singleOf(::VaultRepository)
}
