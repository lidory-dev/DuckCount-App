package database.groupMember

data class GroupMember(
    val uuid: String,
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)
