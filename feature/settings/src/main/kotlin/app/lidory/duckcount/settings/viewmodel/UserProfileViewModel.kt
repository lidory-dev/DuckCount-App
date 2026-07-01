package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserProfileViewModel(
    val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val authenticatedUserUuid = settingsCallBacks.getAuthenticatedUserUuid()

    private val userFullName: StateFlow<String?> =
        settingsCallBacks.getUserFullName(uuid = authenticatedUserUuid ?: "")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

    val draftFullName = MutableStateFlow<String?>(null)

    val state: StateFlow<UserProfileScreenState> =
        combine(userFullName, draftFullName) { savedName, draft ->
            if (authenticatedUserUuid != null && savedName != null) {
                UserProfileScreenState.Content(
                    authenticatedUserUuid = authenticatedUserUuid,
                    fullName = savedName,
                    draftFullName = draft ?: savedName,
                )
            } else {
                UserProfileScreenState.NotFound
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserProfileScreenState.Loading,
        )

    fun onFullNameChanged(fullName: String) {
        draftFullName.value = fullName
    }

    fun onConfirmClicked() {
        val nameToSave = draftFullName.value ?: return

        viewModelScope.launch {
            authenticatedUserUuid?.let { uuid ->
                settingsCallBacks.updateUserFullName(userUuid = uuid, fullName = nameToSave)
                draftFullName.value = null
            }
        }
    }
}

sealed interface UserProfileScreenState {
    data object Loading : UserProfileScreenState
    data object NotFound : UserProfileScreenState
    data class Content(
        val authenticatedUserUuid: String,
        val fullName: String,
        val draftFullName: String,
    ) : UserProfileScreenState
}
