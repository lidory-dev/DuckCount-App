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
import contact.ContactManagement
import group.GroupManagement
import group.GroupSyncAnnouncer
import group.NoGroupSyncAnnouncer
import message.ResendScheduler
import message.di.messageModule
import expense.ExpenseManagement
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
import sync.ForegroundResendScheduler
import sync.ForegroundSync
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
    // Lazily, the sync it runs being built from what books it.
    single<ResendScheduler> { ForegroundResendScheduler(foregroundSync = lazy { get<ForegroundSync>() }, updateRequirement = get()) }
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
    single {
        ContactManagement(
            userRepository = get(),
            contactRequestRepository = get(),
            contactRepository = get(),
            blockedUserRepository = get(),
            contactSync = get(),
            foregroundSync = get(),
            safetyNumbers = get(),
            discoveryUsernameRepository = get(),
            networkConnectivity = get(),
        )
    }
    single {
        ForegroundSync(
            contactSync = get(),
            groupSync = get(),
            oneTimeExpenseSync = get(),
            groupExpenseSync = get(),
        )
    }
    single {
        ExpenseManagement(
            expenseRepository = get(),
            userRepository = get(),
            groupRepository = get(),
            foregroundSync = get(),
            networkConnectivity = get(),
        )
    }
    single {
        GroupManagement(
            groupRepository = get(),
            userRepository = get(),
            expenseRepository = get(),
            contactRequestRepository = get(),
            groupCreator = get(),
            groupEditor = get(),
            groupHistory = get(),
            groupMembershipChanger = get(),
            groupLeaver = get(),
            groupDeleter = get(),
            foregroundSync = get(),
            networkConnectivity = get(),
        )
    }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
    fun getUpdateRequirement(): UpdateRequirement = get()
    fun getAccountAuthentication(): AccountAuthentication = get()
    fun getExpenseManagement(): ExpenseManagement = get()
    fun getGroupManagement(): GroupManagement = get()
    fun getContactManagement(): ContactManagement = get()
}
