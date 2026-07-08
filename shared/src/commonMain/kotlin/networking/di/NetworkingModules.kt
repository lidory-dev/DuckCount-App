package networking.di

import database.data.remote.ExpenseApi
import networking.DuckCountClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val networkingModule = module {
    single { DuckCountClient() }
    singleOf(::ExpenseApi)
}
