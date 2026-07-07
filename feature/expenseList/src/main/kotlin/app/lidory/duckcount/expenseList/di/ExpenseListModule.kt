package app.lidory.duckcount.expenseList.di

import app.lidory.duckcount.expenseList.viewmodel.CreateExpenseViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryDetailViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val expenseListModule = module {
    viewModel { params ->
        ExpenseListViewModel(
            groupUuid = params[0],
            expenseListCallBacks = get(),
        )
    }
    viewModel { params ->
        ExpenseDetailViewModel(
            expenseUuid = params[0],
            expenseDetailCallBacks = get(),
        )
    }
    viewModel { params ->
        CreateExpenseViewModel(
            groupUuid = params[0],
            expenseUuid = params[1],
            createExpenseCallBacks = get(),
        )
    }
    viewModel { params ->
        ExpenseHistoryViewModel(
            groupUuid = params[0],
            expenseUuid = params[1],
            expenseHistoryCallBacks = get(),
        )
    }
    viewModel { params ->
        ExpenseHistoryDetailViewModel(
            groupUuid = params[0],
            expenseUuid = params[1],
            expenseIndex = params[2],
            expenseHistoryCallBacks = get(),
        )
    }
}
