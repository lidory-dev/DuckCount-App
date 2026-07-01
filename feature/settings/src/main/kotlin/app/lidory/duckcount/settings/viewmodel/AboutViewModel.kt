package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.lidory.duckcount.settings.SettingsCallBacks


class AboutViewModel(
    settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val learnMoreLink = settingsCallBacks.learnMoreLink

    val appVersionName = settingsCallBacks.appVersionName
}
