package database.di

import database.AppDatabase
import database.beneficiary.BeneficiaryRepository
import database.beneficiary.BeneficiaryRepositoryImpl
import database.currency.CurrencyRepository
import database.currency.CurrencyRepositoryImpl
import database.expense.ExpenseRepository
import database.expense.ExpenseRepositoryImpl
import database.getRoomDatabase
import database.group.GroupRepository
import database.group.GroupRepositoryImpl
import database.groupMember.GroupMemberRepository
import database.groupMember.GroupMemberRepositoryImpl
import database.user.UserRepository
import database.user.UserRepositoryImpl
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
