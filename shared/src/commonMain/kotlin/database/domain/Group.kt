package database.domain

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Group(
    val uuid: String = Uuid.random().toString(),
    val description: String,
    val title: String,
    val currency: Currency,

    /**
     * Whether the user is no longer a member of the group, removed by its creator, left or its
     * owner having deleted it, which stays, read-only.
     */
    val isLeft: Boolean,

    /** Whether the user is out of it because its owner deleted it. */
    val isDeleted: Boolean = false,
)
