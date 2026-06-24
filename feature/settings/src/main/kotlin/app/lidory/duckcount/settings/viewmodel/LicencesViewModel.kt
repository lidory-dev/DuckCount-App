package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.lidory.duckcount.settings.SettingsCallBacks

class LicencesViewModel(
    settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val licences = settingsCallBacks.licences

}
