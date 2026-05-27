package app.lidory.duckcount.expenseList.di

import app.lidory.duckcount.expenseList.viewmodel.CreateExpenseViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseListViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val expenseListModule = module {
    viewModelOf(::ExpenseListViewModel)
    viewModelOf(::ExpenseDetailViewModel)
    viewModelOf(::CreateExpenseViewModel)
}
