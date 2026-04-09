/**
 * A registration awaiting completion of the local "set up profile" step.
 *
 * Surfaced at startup so the onboarding flow can resume the SetUpProfileScreen instead of
 * sending an already-registered user back to log in.
 */
data class PendingProfile(
    val uuid: String,
    val username: String,
    val hasPasswordBeenChecked: Boolean,
)
