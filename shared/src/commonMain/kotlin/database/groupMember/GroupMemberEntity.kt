package database.groupMember

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val userUuid: String,
    val groupUuid: String,
    val isAdmin: Boolean,
)
