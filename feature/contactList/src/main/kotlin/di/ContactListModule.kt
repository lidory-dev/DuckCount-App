package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.ContactListViewModel

val contactListModule = module {
    viewModelOf(::ContactListViewModel)
}
