package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.CreateExpenseViewModel
import viewmodel.ExpenseDetailViewModel
import viewmodel.ExpenseListViewModel
import viewmodel.UserViewModel

val expenseListModule = module {
    viewModelOf(::ExpenseListViewModel)
    viewModelOf(::ExpenseDetailViewModel)
    viewModelOf(::CreateExpenseViewModel)
    viewModelOf(::UserViewModel)
}
