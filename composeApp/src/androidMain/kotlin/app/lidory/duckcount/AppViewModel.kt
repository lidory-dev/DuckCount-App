package app.lidory.duckcount

import AuthenticationCallBacks
import PendingProfile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import authenticationUtils.SessionExpiry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import networking.UpdateRequirement

/** Where the app should land on launch, resolved once from locally-stored auth state. */
sealed interface Startup {
    /** Still reading local state, show a neutral placeholder, never a real screen. */
    data object Loading : Startup
    data object LoggedIn : Startup
    data object NotLoggedIn : Startup
    data class ResumeRegistration(val pending: PendingProfile) : Startup
}

/**
 * Resolves the launch destination before the navigation graph is built, so the first screen the
 * user sees is the correct one (no onboarding flash, no post-launch redirect). The check is local
 * only, so it's fast and works offline.
 */
class AppViewModel(
    private val authenticationCallBacks: AuthenticationCallBacks,
    private val sessionExpiry: SessionExpiry,
    updateRequirement: UpdateRequirement,
) : ViewModel() {
    private val _startup = MutableStateFlow<Startup>(Startup.Loading)
    val startup = _startup.asStateFlow()

    /**
     * Turns to why the server ended this device's session, once it has. The credentials are
     * already gone by then; all that is left is to restart the app, which takes the user off the
     * screens that can no longer load anything, and tell them why.
     */
    val sessionEnd = sessionExpiry.end

    /**
     * Turns true once the server has refused this build, at whichever request found out, and stays
     * so. Nothing the app does gets past that, so all that is left is to ask the user to update.
     */
    val isUpdateRequired = updateRequirement.isRequired

    init {
        viewModelScope.launch {
            val pending = authenticationCallBacks.getPendingRegistration()
            _startup.value = when {
                pending != null -> Startup.ResumeRegistration(pending)
                authenticationCallBacks.isLoggedIn() -> Startup.LoggedIn
                else -> Startup.NotLoggedIn
            }
        }
    }
}
