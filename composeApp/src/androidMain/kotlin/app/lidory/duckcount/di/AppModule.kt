package app.lidory.duckcount.di

import ContactCallBacks
import app.lidory.duckcount.contactList.ContactCallBacksImp
import app.lidory.duckcount.expenseList.CreateExpenseCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseDetailCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseListCallBacksImpl
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.di.expenseListModule
import app.lidory.duckcount.groupList.GroupListCallBacks
import app.lidory.duckcount.groupList.GroupListCallBacksImpl
import app.lidory.duckcount.groupList.di.groupListModule
import database.di.dbModule
import database.di.dbPlatformModule
import di.contactListModule
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

val expenseListCallBacks = module {
    singleOf(::ExpenseListCallBacksImpl).bind<ExpenseListCallBacks>()
    singleOf(::ExpenseDetailCallBacksImpl).bind<ExpenseDetailCallBacks>()
    singleOf(::CreateExpenseCallBacksImpl).bind<CreateExpenseCallBacks>()
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
    expenseListCallBacks,
)
