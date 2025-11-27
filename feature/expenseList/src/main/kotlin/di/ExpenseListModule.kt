package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.ExpenseDetailViewModel

val expenseListModule = module {
    viewModelOf(::ExpenseDetailViewModel)
}
