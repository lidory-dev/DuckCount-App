package networking.di

import authenticationUtils.SessionExpiry
import database.data.remote.ExpenseApi
import database.data.remote.UserApi
import networking.DuckCountClient
import networking.HaveIBeenPwnedClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val networkingModule = module {
    single { SessionExpiry() }
    single { DuckCountClient(get(), get()) }
    single { HaveIBeenPwnedClient() }
    singleOf(::ExpenseApi)
    singleOf(::UserApi)
}
