package di

import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.di.dbModule
import database.di.dbPlatformModule
import discoveryUsername.di.discoveryModule
import networking.di.networkingModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import profile.di.profileModule
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
        )
    }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
}
