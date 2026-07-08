package di

import database.domain.repository.BeneficiaryRepository
import database.di.dbModule
import database.di.dbPlatformModule
import database.domain.repository.ExpenseRepository
import database.domain.repository.UserRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        modules(
            sharedModule,
            dbModule,
            dbPlatformModule(),
        )
    }
}

class KoinHelper : KoinComponent {
    fun getExpenseRepository(): ExpenseRepository = get()
    fun getBeneficiaryRepository(): BeneficiaryRepository = get()
    fun getUserRepository(): UserRepository = get()
}
