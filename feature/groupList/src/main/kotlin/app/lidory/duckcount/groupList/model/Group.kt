package app.lidory.duckcount.groupList.model

data class Group(
    val uuid: String,
    val description: String,
    val title: String,
)

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val members: List<GroupMember>,
)
