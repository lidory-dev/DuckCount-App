package profile.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import profile.ProfileApi
import profile.ProfileRepository

val profileModule = module {
    singleOf(::ProfileApi)
    singleOf(::ProfileRepository)
}
