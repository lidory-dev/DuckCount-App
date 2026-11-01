package networking.di

import authenticationUtils.SessionExpiry
import database.data.remote.ContactApi
import database.data.remote.DirectMessageApi
import database.data.remote.ExpenseApi
import database.data.remote.SessionApi
import database.data.remote.UserApi
import networking.DuckCountClient
import networking.HaveIBeenPwnedClient
import networking.UpdateRequirement
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val networkingModule = module {
    single { SessionExpiry() }
    single { UpdateRequirement() }
    single { DuckCountClient(get(), get(), getAll(), get()) }
    single { HaveIBeenPwnedClient() }
    singleOf(::ExpenseApi)
    singleOf(::UserApi)
    singleOf(::SessionApi)
    singleOf(::ContactApi)
    singleOf(::DirectMessageApi)
}
