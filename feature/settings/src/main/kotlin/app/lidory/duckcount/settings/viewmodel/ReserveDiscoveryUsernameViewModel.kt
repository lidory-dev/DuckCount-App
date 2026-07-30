package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.ReserveUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

    /** What the user typed, or null while the field still holds the reserved username. */
    private val draftUsername = MutableStateFlow<String?>(null)

    private val isReserving = MutableStateFlow(false)

    private val error = MutableStateFlow<ReserveUsernameError?>(null)

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
                // Nothing typed yet isn't a mistake worth pointing out, the button
                // stays disabled anyway.
                inputError = if (username.isBlank()) {
                    null
                } else {
                    settingsCallBacks.validateUsername(username = username)
                },
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

    fun onUsernameChanged(username: String) {
        draftUsername.value = username
        error.value = null
    }

    fun onReserveClicked() {
        val username = (state.value as? DiscoveryUsernameScreenState.Content)?.username ?: return

        if (settingsCallBacks.validateUsername(username = username) != null) return

        viewModelScope.launch {
            isReserving.value = true
            error.value = null

            val reserveError = settingsCallBacks.reserveUsername(username = username)

            isReserving.value = false
            error.value = reserveError

            // The reservation is the only thing this screen is for, so a successful one
            // hands the user back to the settings list.
            if (reserveError == null) {
                _navigateBack.send(Unit)
            }
        }
    }
}

sealed interface DiscoveryUsernameScreenState {
    data object Loading : DiscoveryUsernameScreenState

    data class Content(
        val username: String,
        val inputError: UsernameInputError?,
        val isReserving: Boolean,
        val error: ReserveUsernameError?,
        val hasReservedUsername: Boolean,
        val isUnchanged: Boolean,
    ) : DiscoveryUsernameScreenState {
        val canReserve: Boolean =
            username.isNotBlank() && inputError == null && !isReserving && !isUnchanged
    }
}
