package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the username screen in both of its modes: reserving a first username and changing
 * an existing one are the same call, the server replaces whatever the user had reserved
 * before, so only the wording differs.
 */
class ReserveDiscoveryUsernameViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val maxUsernameLength = settingsCallBacks.maxUsernameLength

    // True once the username is reserved, the only thing this screen is for. A state rather
    // than an event: the reservation can complete while the app is in the background, where
    // the screen cannot navigate, and the screen then closes once the user is back.
    private val _isReserved = MutableStateFlow(false)
    val isReserved: StateFlow<Boolean> = _isReserved.asStateFlow()

    /** What the user typed, or null while the field still holds the reserved username. */
    private val draftUsername = MutableStateFlow<String?>(null)

    private val isReserving = MutableStateFlow(false)

    private val error = MutableStateFlow<ReserveDiscoveryUsernameError?>(null)

    val state: StateFlow<DiscoveryUsernameScreenState> =
        combine(
            // Deliberately not a StateFlow with a null seed: combine only emits once this
            // has produced a value, which keeps the screen on [Loading] until we know
            // whether a username is already reserved.
            settingsCallBacks.getReservedUsername(),
            draftUsername,
            isReserving,
            error,
        ) { reserved, draft, isReserving, error ->
            val username = draft ?: reserved.orEmpty()

            DiscoveryUsernameScreenState.Content(
                username = username,
                inputError = settingsCallBacks.validateUsername(username = username),
                isReserving = isReserving,
                error = error,
                hasReservedUsername = reserved != null,
                isUnchanged = reserved != null &&
                        reserved == settingsCallBacks.normalizeUsername(username = username),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = DiscoveryUsernameScreenState.Loading,
        )

    init {
        // The vault is what says which handle the account holds, so the local copy the
        // screen reads is refreshed from it on every entry. It shows immediately and is
        // replaced when the fetch lands.
        viewModelScope.launch {
            settingsCallBacks.refreshReservedDiscoveryUsername()
        }
    }

    fun onUsernameChanged(username: String) {
        draftUsername.value = username.lowercase()
        error.value = null
    }

    fun onReserveClicked() {
        // Not once reserved: the screen is closing, and reserving again would only repeat the
        // request.
        if (_isReserved.value) return

        val content = state.value as? DiscoveryUsernameScreenState.Content ?: return

        // Only what the button would reserve: an empty username has no input error, so that
        // alone would let one through.
        if (!content.canReserve) return

        val username = content.username

        viewModelScope.launch {
            isReserving.value = true
            error.value = null

            val reserveError = settingsCallBacks.reserveDiscoveryUsername(username = username)

            // The reservation is the only thing this screen is for, so a successful one
            // hands the user back to the settings list.
            if (reserveError == null) _isReserved.value = true

            isReserving.value = false
            error.value = reserveError
        }
    }
}

sealed interface DiscoveryUsernameScreenState {
    data object Loading : DiscoveryUsernameScreenState

    data class Content(
        val username: String,
        val inputError: UsernameInputError?,
        val isReserving: Boolean,
        val error: ReserveDiscoveryUsernameError?,
        val hasReservedUsername: Boolean,
        val isUnchanged: Boolean,
    ) : DiscoveryUsernameScreenState {
        val canReserve: Boolean =
            username.isNotBlank() && inputError == null && !isReserving && !isUnchanged
    }
}
