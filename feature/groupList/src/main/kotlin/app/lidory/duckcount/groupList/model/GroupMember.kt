package app.lidory.duckcount.groupList.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class GroupMember(
    val uuid: String = Uuid.random().toString(),
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)
