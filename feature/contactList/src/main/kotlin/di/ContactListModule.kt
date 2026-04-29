package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.ContactDetailViewModel
import viewmodel.ContactListViewModel

val contactListModule = module {
    viewModelOf(::ContactListViewModel)
    viewModelOf(::ContactDetailViewModel)
}
