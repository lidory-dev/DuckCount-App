package app.lidory.duckcount.settings.di

import app.lidory.duckcount.settings.viewmodel.AboutViewModel
import app.lidory.duckcount.settings.viewmodel.AccountSettingsViewModel
import app.lidory.duckcount.settings.viewmodel.ChangeFullNameViewModel
import app.lidory.duckcount.settings.viewmodel.LicencesViewModel
import app.lidory.duckcount.settings.viewmodel.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsModule = module {
    viewModelOf(::SettingsViewModel)
    viewModelOf(::AccountSettingsViewModel)
    viewModelOf(::ChangeFullNameViewModel)
    viewModelOf(::AboutViewModel)
    viewModelOf(::LicencesViewModel)
}
