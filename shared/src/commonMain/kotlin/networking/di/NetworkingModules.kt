package networking.di

import database.data.remote.ExpenseApi
import database.data.remote.UserApi
import networking.DuckCountClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val networkingModule = module {
    single { DuckCountClient() }
    singleOf(::ExpenseApi)
    singleOf(::UserApi)
}
