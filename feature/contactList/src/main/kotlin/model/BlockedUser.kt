package model

/** Somebody the user blocked. */
data class BlockedUser(
    val userUuid: String,

    /** The name the user saw them by as they blocked them, null when there was none. */
    val fullName: String?,
)
