package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.lidory.duckcount.settings.SettingsCallBacks

class SettingsViewModel(
    settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val sourceCodeLink = settingsCallBacks.sourceCodeLink

    val termsOfUseLink = settingsCallBacks.termsOfUseLink

    val privacyPolicyLink = settingsCallBacks.privacyPolicyLink
}
