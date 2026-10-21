package di

import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.di.dbModule
import database.di.dbPlatformModule
import discoveryUsername.di.discoveryModule
import channel.di.channelModule
import message.NoResendScheduler
import message.ResendScheduler
import message.di.messageModule
import expense.di.expenseModule
import mls.di.mlsModule
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
            pushModule,
            iosPlatformModule,
        )
    }
}

/** What the shared layer only knows as an interface, as iOS implements it so far. */
private val iosPlatformModule = module {
    single<ResendScheduler> { NoResendScheduler }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
}
