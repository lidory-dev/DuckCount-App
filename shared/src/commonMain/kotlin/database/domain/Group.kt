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
     * Whether the user is no longer a member of the group, removed by its creator or left, which
     * stays, read-only.
     */
    val isLeft: Boolean,
)
