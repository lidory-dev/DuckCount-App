package database.data.local.entity

import androidx.room.Entity

@Entity(primaryKeys = ["userUuid", "groupUuid"])
data class GroupMemberEntity(
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)
