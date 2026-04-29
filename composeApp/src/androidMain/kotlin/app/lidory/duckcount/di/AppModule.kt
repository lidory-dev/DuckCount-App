package app.lidory.duckcount.di

import ContactCallBacks
import app.lidory.duckcount.contactList.ContactCallBacksImp
import database.di.dbModule
import database.di.dbPlatformModule
import di.contactListModule
import di.expenseListModule
import di.sharedModule
import networking.di.networkingModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val contactCallBacks = module {
    singleOf(::ContactCallBacksImp).bind<ContactCallBacks>()
}

val appModule = listOf(
    sharedModule,
    dbModule,
    dbPlatformModule(),
    networkingModule,
    expenseListModule,
    contactListModule,
    contactCallBacks,
)
