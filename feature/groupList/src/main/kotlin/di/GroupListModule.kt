package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.GroupListViewModel

val groupListModule = module {
    viewModelOf(::GroupListViewModel)
}
