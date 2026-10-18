package profile.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import profile.ProfileApi
import profile.ProfileRepository
import profile.ProfileStore

val profileModule = module {
    singleOf(::ProfileApi) { bind<ProfileStore>() }
    singleOf(::ProfileRepository)
}
