package networking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Announces that the server no longer supports this build, so the UI can replace the whole app
 * with the screen asking for an update, and whatever runs in the background can stop trying.
 *
 * Raised when any request comes back `426`, see [nameClientBuild]. Until a first request has been
 * made, offline or signed out, nothing has said so and the app runs as usual.
 *
 * State rather than an event stream on purpose: a `426` can land before anything is collecting
 * (during startup, or in a worker with no screen up), and that must not be the one signal that
 * gets dropped. Held in memory alone and never reset: updating the app ends the process, and a new
 * process asks again at its first request, so that a minimum lowered again heals by itself.
 */
class UpdateRequirement {
    private val _isRequired = MutableStateFlow(false)

    /** Whether the server has refused this build, false until it does. */
    val isRequired: StateFlow<Boolean> = _isRequired.asStateFlow()

    fun signal() {
        _isRequired.value = true
    }
}
