package app.lidory.duckcount.expenseList.di

import app.lidory.duckcount.expenseList.viewmodel.CreateExpenseViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val expenseListModule = module {
    viewModel { params ->
        ExpenseListViewModel(
            groupUuid = params[0],
            expenseListCallBacks = get(),
        )
    }
    viewModelOf(::ExpenseDetailViewModel)
    viewModel { params ->
        CreateExpenseViewModel(
            groupUuid = params[0],
            createExpenseCallBacks = get(),
        )
    }
}
