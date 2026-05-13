package app.lidory.duckcount.di

import ContactCallBacks
import GroupListCallBacks
import app.lidory.duckcount.contactList.ContactCallBacksImp
import app.lidory.duckcount.groupList.GroupListCallBacksImpl
import database.di.dbModule
import database.di.dbPlatformModule
import di.contactListModule
import di.expenseListModule
import di.groupListModule
import di.sharedModule
import networking.di.networkingModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val contactCallBacks = module {
    singleOf(::ContactCallBacksImp).bind<ContactCallBacks>()
}
val groupCallBacks = module {
    singleOf(::GroupListCallBacksImpl).bind<GroupListCallBacks>()
}

val appModule = listOf(
    sharedModule,
    dbModule,
    dbPlatformModule(),
    networkingModule,
    expenseListModule,
    contactListModule,
    contactCallBacks,
    groupListModule,
    groupCallBacks,
)
