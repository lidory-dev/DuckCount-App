package app.lidory.duckcount.di

import app.lidory.duckcount.viewmodel.ExpenseDetailViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    viewModelOf(::ExpenseDetailViewModel)
}
