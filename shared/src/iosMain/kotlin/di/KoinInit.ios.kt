package di

import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.di.dbModule
import database.di.dbPlatformModule
import networking.di.networkingModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        modules(
            sharedModule,
            dbModule,
            dbPlatformModule(),
            networkingModule,
        )
    }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
}
