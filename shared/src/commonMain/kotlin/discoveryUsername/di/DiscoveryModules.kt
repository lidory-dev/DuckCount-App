package discoveryUsername.di

import discoveryUsername.DiscoveryUsernameRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val discoveryModule = module {
    singleOf(::DiscoveryUsernameRepository)
}
