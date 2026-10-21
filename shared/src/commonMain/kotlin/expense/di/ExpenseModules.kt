package expense.di

import expense.ExpenseArrivals
import expense.ExpenseChanges
import expense.ExpenseKeyDelivery
import expense.ExpenseKeyPull
import expense.OneTimeExpenseKeyRepository
import expense.OneTimeExpenseSync
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val expenseModule = module {
    singleOf(::OneTimeExpenseKeyRepository)
    singleOf(::ExpenseArrivals)
    singleOf(::ExpenseChanges)
    single {
        ExpenseKeyDelivery(
            owedExpenseKeyDao = get(),
            keyRepository = get(),
            directMessageSender = get(),
            resendScheduler = get(),
        )
    }
    single {
        ExpenseKeyPull(
            pendingExpenseKeyDao = get(),
            keyRepository = get(),
            directMessageSender = get(),
            resendScheduler = get(),
        )
    }
    singleOf(::OneTimeExpenseSync)
}
