package database.di

import database.AppDatabase
import database.domain.repository.BeneficiaryRepository
import database.domain.repository.CurrencyRepository
import database.data.repository.BeneficiaryRepositoryImpl
import database.data.repository.CurrencyRepositoryImpl
import database.data.repository.ExpenseRepositoryImpl
import database.data.repository.GroupMemberRepositoryImpl
import database.data.repository.GroupRepositoryImpl
import database.data.repository.UserRepositoryImpl
import database.domain.repository.ExpenseRepository
import database.getRoomDatabase
import database.domain.repository.GroupRepository
import database.domain.repository.GroupMemberRepository
import database.domain.repository.UserRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

expect fun dbPlatformModule(): Module

val dbModule = module {
    singleOf(::UserRepositoryImpl).bind<UserRepository>()
    singleOf(::ExpenseRepositoryImpl).bind<ExpenseRepository>()
    singleOf(::CurrencyRepositoryImpl).bind<CurrencyRepository>()
    singleOf(::BeneficiaryRepositoryImpl).bind<BeneficiaryRepository>()
    singleOf(::GroupRepositoryImpl).bind<GroupRepository>()
    singleOf(::GroupMemberRepositoryImpl).bind<GroupMemberRepository>()

    single { getRoomDatabase(get()) }

    single { get<AppDatabase>().userDao }
    single { get<AppDatabase>().expenseDao }
    single { get<AppDatabase>().currencyDao }
    single { get<AppDatabase>().beneficiaryDao }
    single { get<AppDatabase>().groupDao }
    single { get<AppDatabase>().groupMemberDao }
}
