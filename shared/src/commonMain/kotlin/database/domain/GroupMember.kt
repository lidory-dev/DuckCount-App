package database.domain

data class GroupMember(
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)