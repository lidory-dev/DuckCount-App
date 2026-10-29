package app.lidory.duckcount.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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

    /**
     * What the user typed, as typed, or null while the field still holds the reserved username.
     * Neither lowercased nor trimmed here: rewriting what is being typed would hand the keyboard a
     * new text at every capital, and drop the keys already on their way to it, and a space typed
     * after a word would be swallowed, the cursor moved back over it. The field shows it
     * lowercased, and [DiscoveryUsernameScreenState.Content.username], what is checked and
     * reserved, is lowercased and without the whitespace around it.
     *
     * Snapshot state rather than a flow: the field has to get its text back in the same frame as
     * the edit, which a collected flow only delivers a frame later.
     */
    var draftUsername by mutableStateOf<String?>(null)
        private set

    private val isReserving = MutableStateFlow(false)

    private val error = MutableStateFlow<ReserveDiscoveryUsernameError?>(null)

    val state: StateFlow<DiscoveryUsernameScreenState> =
        combine(
            // Deliberately not a StateFlow with a null seed: combine only emits once this
            // has produced a value, which keeps the screen on [Loading] until we know
            // whether a username is already reserved.
            settingsCallBacks.getReservedUsername(),
            snapshotFlow { draftUsername },
            isReserving,
            error,
        ) { reserved, draft, isReserving, error ->
            val username = draft?.trim()?.lowercase() ?: reserved.orEmpty()

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
        draftUsername = username
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
        /**
         * The username as it is checked and reserved: lowercased, as every username is, and
         * without the whitespace around it, so that one pasted with a stray space still goes
         * through, a space never being valid.
         */
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
