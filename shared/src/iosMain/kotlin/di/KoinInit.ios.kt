package di

import account.di.accountModule
import authenticationUtils.SessionExpiry
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
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import profile.di.profileModule
import settings.SettingsManagement
import sync.ForegroundResendScheduler
import sync.ForegroundSync
import push.ApnsPushRegistrar
import push.PushRegistrar
import push.SharedWakeList
import push.SharedWakeListStore
import push.WakeListStore
import push.di.pushModule
import sync.AnnouncingGroupSyncAnnouncer
import sync.CatchUpScheduler
import sync.SyncAnnouncer
import vault.di.vaultModule

/**
 * Starts the shared module, with what the app implements of it: [sharedWakeList], the wake list it
 * keeps for its notification service extension, [syncAnnouncer], which posts what a sync read, and
 * [catchUpScheduler], which has iOS run a sync in the background. All outlive every session, as the
 * app's own objects do, see [restartSession].
 */
fun initKoin(sharedWakeList: SharedWakeList, syncAnnouncer: SyncAnnouncer, catchUpScheduler: CatchUpScheduler) {
    platform = Platform(
        sharedWakeList = sharedWakeList,
        syncAnnouncer = syncAnnouncer,
        catchUpScheduler = catchUpScheduler,
    )

    startKoin {
        modules(listOf(sharedModule) + sessionModules)
    }
}

/** What the app handed [initKoin]. */
private class Platform(
    val sharedWakeList: SharedWakeList,
    val syncAnnouncer: SyncAnnouncer,
    val catchUpScheduler: CatchUpScheduler,
)

private lateinit var platform: Platform

/**
 * Every module but [sharedModule], which holds the two KSafe stores: what a session's end reloads,
 * see [restartSession]. The stores stay, as they would not in a process of their own: two of them on
 * one file would each keep its own copy of what it holds in memory.
 *
 * Built once, unloading the very modules that were loaded, and on first use: [iosPlatformModule] is
 * declared further down, and would be null yet.
 */
internal val sessionModules: List<Module> by lazy {
    listOf(
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

/**
 * What the shared layer only knows as an interface, as iOS implements it so far, and what only iOS
 * runs from it, the Android app holding its own counterparts.
 */
private val iosPlatformModule = module {
    // Lazily, the sync it runs being built from what books it.
    single {
        ForegroundResendScheduler(
            foregroundSync = lazy { get<ForegroundSync>() },
            updateRequirement = get(),
            catchUp = platform.catchUpScheduler,
        )
    }
        .bind<ResendScheduler>()
    single<WakeListStore> { SharedWakeListStore(shared = platform.sharedWakeList) }
    single<SyncAnnouncer> { platform.syncAnnouncer }
    single<GroupSyncAnnouncer> { AnnouncingGroupSyncAnnouncer(announcer = get()) }
    single { ApnsPushRegistrar(pushRepository = get()) }.bind<PushRegistrar>()
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
            networkConnectivity = get(),
        )
    }
    single {
        SettingsManagement(
            duckCountClient = get(),
            userRepository = get(),
            profileRepository = get(),
            discoveryUsernameRepository = get(),
            accountDeletion = get(),
            passwordChange = get(),
            pushRegistrar = get(),
            networkConnectivity = get(),
        )
    }
    single {
        ForegroundSync(
            contactSync = get(),
            groupSync = get(),
            oneTimeExpenseSync = get(),
            groupExpenseSync = get(),
            contactArrivals = get(),
            expenseArrivals = get(),
            expenseChanges = get(),
            groupExpenseUpdates = get(),
            announcer = get(),
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
    fun getSettingsManagement(): SettingsManagement = get()
    fun getSessionExpiry(): SessionExpiry = get()
    fun getPushRegistrar(): ApnsPushRegistrar = get()
    fun getForegroundSync(): ForegroundSync = get()
}
