package database.di

import contact.ContactArrivals
import contact.ContactSync
import database.AppDatabase
import database.data.repository.BeneficiaryRepository
import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import database.data.repository.CurrencyRepository
import database.data.repository.ExpenseRepository
import database.data.repository.GroupMemberRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import database.getRoomDatabase
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

expect fun dbPlatformModule(): Module

val dbModule = module {
    singleOf(::UserRepository)
    singleOf(::ExpenseRepository)
    singleOf(::CurrencyRepository)
    singleOf(::BeneficiaryRepository)
    singleOf(::GroupRepository)
    singleOf(::GroupMemberRepository)
    singleOf(::ContactRequestRepository)
    singleOf(::ContactRepository)
    singleOf(::ContactSync)
    singleOf(::ContactArrivals)

    single { getRoomDatabase(get()) }

    single { get<AppDatabase>().userDao }
    single { get<AppDatabase>().expenseDao }
    single { get<AppDatabase>().currencyDao }
    single { get<AppDatabase>().beneficiaryDao }
    single { get<AppDatabase>().groupDao }
    single { get<AppDatabase>().groupMemberDao }
    single { get<AppDatabase>().contactRequestDao }
    single { get<AppDatabase>().contactDao }
    single { get<AppDatabase>().mlsRecordDao }

    single { get<AppDatabase>().localDataDao }
}
