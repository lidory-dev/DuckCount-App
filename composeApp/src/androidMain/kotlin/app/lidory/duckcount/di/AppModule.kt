package app.lidory.duckcount.di

import AuthenticationCallBacks
import account.di.accountModule
import ContactCallBacks
import app.lidory.duckcount.AppViewModel
import app.lidory.duckcount.authentication.AuthenticationCallBacksImpl
import app.lidory.duckcount.contactList.ContactCallBacksImp
import app.lidory.duckcount.expenseList.CreateExpenseCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseDetailCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseHistoryCallBacksImpl
import app.lidory.duckcount.expenseList.ExpenseListCallBacksImpl
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.di.expenseListModule
import app.lidory.duckcount.groupList.GroupBalanceCallBacksImpl
import app.lidory.duckcount.groupList.GroupListCallBacksImpl
import app.lidory.duckcount.groupList.GroupSettingsCallBacksImpl
import app.lidory.duckcount.groupList.GroupSettleUpCallBacksImpl
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.callBacks.GroupSettleUpCallBacks
import app.lidory.duckcount.groupList.di.groupListModule
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.push.ContactNotifier
import app.lidory.duckcount.push.ExpenseNotifier
import app.lidory.duckcount.push.NotificationLeftovers
import app.lidory.duckcount.push.UnifiedPushRegistrar
import app.lidory.duckcount.push.WorkManagerResendScheduler
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.SettingsCallBacksImpl
import app.lidory.duckcount.settings.di.settingsModule
import authenticationUtils.SessionLeftovers
import database.di.dbModule
import database.di.dbPlatformModule
import di.authenticationModule
import di.contactListModule
import di.sharedModule
import discoveryUsername.di.discoveryModule
import channel.di.channelModule
import message.ResendScheduler
import message.di.messageModule
import expense.di.expenseModule
import mls.di.mlsModule
import networking.di.networkingModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import profile.di.profileModule
import push.PushRegistrar
import push.di.pushModule
import vault.di.vaultModule

val authenticationCallBacksModule = module {
    singleOf(::AuthenticationCallBacksImpl).bind<AuthenticationCallBacks>()
}

val appViewModelModule = module {
    viewModelOf(::AppViewModel)
}

val contactCallBacks = module {
    singleOf(::ContactCallBacksImp).bind<ContactCallBacks>()
}

/** The Android half of push, which the shared layer only knows as an interface. */
val pushPlatformModule = module {
    single { UnifiedPushRegistrar(androidContext(), get()) }.bind<PushRegistrar>()
    single { NotificationLeftovers(androidContext()) }.bind<SessionLeftovers>()
    single { ContactNotifier(androidContext()) }
    single { ExpenseNotifier(androidContext()) }
    single { WorkManagerResendScheduler(androidContext()) }.bind<ResendScheduler>()
}
val groupCallBacks = module {
    singleOf(::GroupListCallBacksImpl).bind<GroupListCallBacks>()
    singleOf(::GroupBalanceCallBacksImpl).bind<GroupBalanceCallBacks>()
    singleOf(::GroupSettingsCallBacksImpl).bind<GroupSettingsCallBacks>()
    singleOf(::GroupSettleUpCallBacksImpl).bind<GroupSettleUpCallBacks>()
}

val expenseListCallBacks = module {
    single { NetworkConnectivity(androidContext()) }

    singleOf(::ExpenseListCallBacksImpl).bind<ExpenseListCallBacks>()
    singleOf(::ExpenseDetailCallBacksImpl).bind<ExpenseDetailCallBacks>()
    singleOf(::CreateExpenseCallBacksImpl).bind<CreateExpenseCallBacks>()
    singleOf(::ExpenseHistoryCallBacksImpl).bind<ExpenseHistoryCallBacks>()
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
    vaultModule,
    profileModule,
    discoveryModule,
    mlsModule,
    expenseModule,
    messageModule,
    channelModule,
    pushModule,
    pushPlatformModule,
    accountModule,
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
