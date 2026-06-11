package app.lidory.duckcount.groupList.di

import app.lidory.duckcount.groupList.viewmodel.CreateGroupViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupBalanceViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupDebtViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupDetailViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupListViewModel
import app.lidory.duckcount.groupList.viewmodel.GroupSettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val groupListModule = module {
    viewModelOf(::GroupListViewModel)
    viewModelOf(::CreateGroupViewModel)
    viewModel { params ->
        CreateGroupViewModel(
            groupUuid = params[0],
            groupListCallBacks = get(),
        )
    }
    viewModelOf(::GroupDetailViewModel)
    viewModelOf(::GroupBalanceViewModel)
    viewModelOf(::GroupSettingsViewModel)
    viewModelOf(::GroupDebtViewModel)
}
