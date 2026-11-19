package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(
    settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val sourceCodeLink = settingsCallBacks.sourceCodeLink

    val termsOfUseLink = settingsCallBacks.termsOfUseLink

    val privacyPolicyLink = settingsCallBacks.privacyPolicyLink

    /** The user's own UUID, which their avatar's color comes from, as on their contacts' devices. */
    val authenticatedUserUuid: String? = settingsCallBacks.getAuthenticatedUserUuid()

    private val username: String? = settingsCallBacks.getUserUsername()

    /**
     * The name the profile card at the top of the list shows: the profile's, or else the username
     * the user logs in with, right after a login that has not read the profile yet. Null until read.
     */
    val displayName: StateFlow<String?> =
        (authenticatedUserUuid?.let { settingsCallBacks.getUserFullName(uuid = it) } ?: flowOf(null))
            .map { fullName -> fullName?.takeIf { it.isNotBlank() } ?: username.orEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )
}
