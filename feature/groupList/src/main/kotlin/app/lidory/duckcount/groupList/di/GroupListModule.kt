package app.lidory.duckcount.groupList.di

import app.lidory.duckcount.groupList.viewmodel.CreateGroupViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupBalanceViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupDetailViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupListViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val groupListModule = module {
    viewModelOf(::GroupListViewModel)
    viewModelOf(::CreateGroupViewModel)
    viewModelOf(::GroupDetailViewModel)
    viewModelOf(::GroupBalanceViewModel)
}
