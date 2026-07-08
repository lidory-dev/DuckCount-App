package database.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class GroupWithMembersEntity(
    @Embedded val group: GroupEntity,

    @Relation(
        parentColumn = "uuid",
        entityColumn = "groupUuid",
    )
    val groupMembers: List<GroupMemberEntity>,
)