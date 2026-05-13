package database.group

import androidx.room.Embedded
import androidx.room.Relation
import database.groupMember.GroupMemberEntity

data class GroupWithMembersEntity(
    @Embedded val group: GroupEntity,

    @Relation(
        parentColumn = "uuid",
        entityColumn = "groupUuid",
    )
    val groupMembers: List<GroupMemberEntity>,
)
