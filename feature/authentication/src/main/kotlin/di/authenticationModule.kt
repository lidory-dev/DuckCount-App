package di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import viewmodel.LoginViewModel
import viewmodel.RegisterViewModel
import viewmodel.UserInformationViewModel

val authenticationModule = module {
    viewModelOf(::RegisterViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::UserInformationViewModel)
}
