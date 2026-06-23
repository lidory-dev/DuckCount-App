package database.domain

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
    val groupMembers: List<GroupMember>,
)
