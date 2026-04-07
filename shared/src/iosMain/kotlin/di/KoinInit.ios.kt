package di

import database.beneficiary.BeneficiaryRepository
import database.di.dbModule
import database.di.dbPlatformModule
import database.expense.ExpenseRepository
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
}
