package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.ExpenseDetailViewModel
import viewmodel.ExpenseListViewModel

val expenseListModule = module {
    viewModelOf(::ExpenseListViewModel)
    viewModelOf(::ExpenseDetailViewModel)
}
