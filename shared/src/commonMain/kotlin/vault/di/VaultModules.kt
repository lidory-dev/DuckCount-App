package vault.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import vault.MemberProfileVault
import vault.ProfileKeyVault
import vault.VaultApi
import vault.VaultRepository

val vaultModule = module {
    singleOf(::VaultApi)
    singleOf(::VaultRepository) {
        bind<ProfileKeyVault>()
        bind<MemberProfileVault>()
    }
}
