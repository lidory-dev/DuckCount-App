package database.group

import database.groupMember.GroupMember

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val groupMembers: List<GroupMember>,
)
