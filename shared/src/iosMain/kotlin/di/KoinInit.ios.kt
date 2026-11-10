package di

import account.di.accountModule
import authentication.AccountAuthentication
import authentication.AppAttester
import authentication.NetworkConnectivity
import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.di.dbModule
import database.di.dbPlatformModule
import discoveryUsername.di.discoveryModule
import channel.di.channelModule
import group.GroupSyncAnnouncer
import group.NoGroupSyncAnnouncer
import message.NoResendScheduler
import message.ResendScheduler
import message.di.messageModule
import expense.di.expenseModule
import group.di.groupModule
import mls.di.mlsModule
import networking.UpdateRequirement
import networking.di.networkingModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.dsl.module
import profile.di.profileModule
import push.di.pushModule
import vault.di.vaultModule

fun initKoin() {
    startKoin {
        modules(
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
            groupModule,
            pushModule,
            accountModule,
            iosPlatformModule,
        )
    }
}

/**
 * What the shared layer only knows as an interface, as iOS implements it so far, and what only iOS
 * runs from it, the Android app holding its own counterparts.
 */
private val iosPlatformModule = module {
    single<ResendScheduler> { NoResendScheduler }
    single<GroupSyncAnnouncer> { NoGroupSyncAnnouncer }
    single { NetworkConnectivity() }
    single { AppAttester() }
    single {
        AccountAuthentication(
            duckCountClient = get(),
            haveIBeenPwnedClient = get(),
            profileRepository = get(),
            discoveryUsernameRepository = get(),
            keyPackageRepository = get(),
            networkConnectivity = get(),
            appAttester = get(),
        )
    }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
    fun getUpdateRequirement(): UpdateRequirement = get()
    fun getAccountAuthentication(): AccountAuthentication = get()
}
