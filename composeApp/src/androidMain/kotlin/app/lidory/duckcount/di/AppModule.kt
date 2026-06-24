package app.lidory.duckcount.di

import AuthenticationCallBacks
import ContactCallBacks
import app.lidory.duckcount.AppViewModel
import app.lidory.duckcount.authentication.AuthenticationCallBacksImpl
import app.lidory.duckcount.contactList.ContactCallBacksImp
import app.lidory.duckcount.expenseList.CreateExpenseCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseDetailCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseListCallBacksImpl
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.di.expenseListModule
import app.lidory.duckcount.groupList.GroupBalanceCallBacksImpl
import app.lidory.duckcount.groupList.GroupListCallBacksImpl
import app.lidory.duckcount.groupList.GroupSettingsCallBacksImpl
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.di.groupListModule
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.SettingsCallBacksImpl
import app.lidory.duckcount.settings.di.settingsModule
import database.di.dbModule
import database.di.dbPlatformModule
import di.authenticationModule
import di.contactListModule
import di.sharedModule
import networking.di.networkingModule
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val authenticationCallBacksModule = module {
    singleOf(::AuthenticationCallBacksImpl).bind<AuthenticationCallBacks>()
}

val appViewModelModule = module {
    viewModelOf(::AppViewModel)
}

val contactCallBacks = module {
    singleOf(::ContactCallBacksImp).bind<ContactCallBacks>()
}
val groupCallBacks = module {
    singleOf(::GroupListCallBacksImpl).bind<GroupListCallBacks>()
    singleOf(::GroupBalanceCallBacksImpl).bind<GroupBalanceCallBacks>()
    singleOf(::GroupSettingsCallBacksImpl).bind<GroupSettingsCallBacks>()
}

val expenseListCallBacks = module {
    singleOf(::ExpenseListCallBacksImpl).bind<ExpenseListCallBacks>()
    singleOf(::ExpenseDetailCallBacksImpl).bind<ExpenseDetailCallBacks>()
    singleOf(::CreateExpenseCallBacksImpl).bind<CreateExpenseCallBacks>()
}

val settingsCallBacks = module {
    singleOf(::SettingsCallBacksImpl).bind<SettingsCallBacks>()
}

val appModule = listOf(
    appViewModelModule,
    sharedModule,
    dbModule,
    dbPlatformModule(),
    networkingModule,
    expenseListModule,
    authenticationModule,
    authenticationCallBacksModule,
    contactListModule,
    contactCallBacks,
    groupListModule,
    groupCallBacks,
    expenseListCallBacks,
    settingsModule,
    settingsCallBacks,
)
