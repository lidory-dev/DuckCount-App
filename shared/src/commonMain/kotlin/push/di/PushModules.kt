package push.di

import authenticationUtils.SessionLeftovers
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import push.PushApi
import push.PushRepository
import push.WakeList

val pushModule = module {
    singleOf(::PushApi)
    singleOf(::PushRepository)

    // The store is bound on Android only, the one platform with push so far.
    single { WakeList(get(), get(), getOrNull()) }.bind<SessionLeftovers>()
}
