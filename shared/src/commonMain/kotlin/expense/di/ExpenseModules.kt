package expense.di

import expense.ExpenseArrivals
import expense.ExpenseChanges
import expense.OneTimeExpenseKeyRepository
import expense.OneTimeExpenseSync
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val expenseModule = module {
    singleOf(::OneTimeExpenseKeyRepository)
    singleOf(::ExpenseArrivals)
    singleOf(::ExpenseChanges)
    singleOf(::OneTimeExpenseSync)
}
