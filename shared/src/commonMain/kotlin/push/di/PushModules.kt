package push.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import push.PushApi
import push.PushRepository

val pushModule = module {
    singleOf(::PushApi)
    singleOf(::PushRepository)
}
