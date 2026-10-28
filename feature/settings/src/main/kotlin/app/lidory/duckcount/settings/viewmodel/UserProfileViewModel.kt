package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.android.ui.text.limitedTo
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

    /** The longest full name the form takes, see [onFullNameChanged]. */
    val maxFullNameLength = settingsCallBacks.maxFullNameLength

    val draftFullName = MutableStateFlow<String?>(null)

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
            draftFullName,
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

    /**
     * Takes [fullName], the field's text after an edit, held to [maxFullNameLength]: of what the
     * edit inserts past it, only what fits is kept, see [limitedTo]. The field showed the saved name
     * until the first edit made a draft of it.
     */
    fun onFullNameChanged(fullName: String) {
        val shown = draftFullName.value ?: (state.value as? UserProfileScreenState.Content)?.fullName.orEmpty()

        draftFullName.value = fullName.limitedTo(maxLength = maxFullNameLength, previous = shown)
        error.value = null
    }

    fun onConfirmClicked() {
        val uuid = authenticatedUserUuid ?: return
        val nameToSave = draftFullName.value ?: return

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
            if (saveError == null) draftFullName.value = null
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
