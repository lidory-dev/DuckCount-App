package app.lidory.duckcount.groupList.model

data class GroupMemberWithBalance(
    val member: GroupMemberWithFullName,
    val balance: String = "",
)