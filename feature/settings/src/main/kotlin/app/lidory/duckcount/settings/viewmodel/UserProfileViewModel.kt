package app.lidory.duckcount.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.ProfileSaveError
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

    /** The longest full name the form takes, which its field stops at. */
    val maxFullNameLength = settingsCallBacks.maxFullNameLength

    /**
     * What the user typed, or null while the field still holds the saved name.
     *
     * Snapshot state rather than a flow: the field has to get its text back in the same frame as
     * the edit, which a collected flow only delivers a frame later.
     */
    var draftFullName by mutableStateOf<String?>(null)
        private set

    private val isSaving = MutableStateFlow(false)

    private val error = MutableStateFlow<ProfileSaveError?>(null)

    init {
        // The server holds the profile, refreshing on open is what makes a change from
        // another device show up here. It is not waited on, the cached copy is displayed
        // straight away and replaced when the fetch lands.
        authenticatedUserUuid?.let { uuid ->
            viewModelScope.launch { settingsCallBacks.refreshUserProfile(userUuid = uuid) }
        }
    }

    val state: StateFlow<UserProfileScreenState> = when (authenticatedUserUuid) {
        null -> MutableStateFlow(UserProfileScreenState.NotFound)

        else -> combine(
            settingsCallBacks.getUserFullName(uuid = authenticatedUserUuid),
            snapshotFlow { draftFullName },
            isSaving,
            error,
        ) { savedName, draft, saving, saveError ->
            val fullName = savedName.orEmpty()

            UserProfileScreenState.Content(
                authenticatedUserUuid = authenticatedUserUuid,
                fullName = fullName,
                draftFullName = draft ?: fullName,
                isSaving = saving,
                error = saveError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserProfileScreenState.Loading,
        )
    }

    fun onFullNameChanged(fullName: String) {
        draftFullName = fullName
        error.value = null
    }

    fun onConfirmClicked() {
        val uuid = authenticatedUserUuid ?: return
        val nameToSave = draftFullName ?: return

        viewModelScope.launch {
            isSaving.value = true
            error.value = null

            val saveError = settingsCallBacks.updateUserProfile(
                userUuid = uuid,
                fullName = nameToSave,
            )

            isSaving.value = false
            error.value = saveError

            // Only a save the server took is worth dropping the draft for; otherwise
            // the user keeps what they typed and can try again.
            if (saveError == null) draftFullName = null
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
        val isSaving: Boolean,
        val error: ProfileSaveError?,
    ) : UserProfileScreenState {
        val canSave: Boolean =
            draftFullName.isNotBlank() && draftFullName != fullName && !isSaving
    }
}
