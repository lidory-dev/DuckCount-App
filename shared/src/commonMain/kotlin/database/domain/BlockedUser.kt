package database.domain

/** Somebody the user blocked, see [database.data.local.entity.BlockedUserEntity]. */
data class BlockedUser(
    val userUuid: String,

    /** The name the user saw them by as they blocked them, null when there was none. */
    val fullName: String?,
)
