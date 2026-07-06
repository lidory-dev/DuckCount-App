package app.lidory.duckcount.settings.di

import app.lidory.duckcount.settings.viewmodel.AboutViewModel
import app.lidory.duckcount.settings.viewmodel.AccountDeletionViewModel
import app.lidory.duckcount.settings.viewmodel.AccountViewModel
import app.lidory.duckcount.settings.viewmodel.LicencesViewModel
import app.lidory.duckcount.settings.viewmodel.PasswordChangeViewModel
import app.lidory.duckcount.settings.viewmodel.SettingsViewModel
import app.lidory.duckcount.settings.viewmodel.UserProfileViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsModule = module {
    viewModelOf(::SettingsViewModel)
    viewModelOf(::UserProfileViewModel)
    viewModelOf(::AccountViewModel)
    viewModelOf(::PasswordChangeViewModel)
    viewModelOf(::AccountDeletionViewModel)
    viewModelOf(::AboutViewModel)
    viewModelOf(::LicencesViewModel)
}
