package app.lidory.duckcount.groupList.model

data class Group(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
)

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
    val members: List<GroupMember>,
)
