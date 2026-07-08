package app.lidory.duckcount.groupList.model

import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
data class GroupMember(
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)
