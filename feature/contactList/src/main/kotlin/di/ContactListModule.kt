package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.ContactDetailViewModel
import viewmodel.ContactListViewModel
import viewmodel.CreateContactViewModel

val contactListModule = module {
    viewModelOf(::ContactListViewModel)
    viewModelOf(::ContactDetailViewModel)
    viewModelOf(::CreateContactViewModel)
}
