package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import database.data.local.entity.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMemberDao {
    @Insert
    suspend fun insert(groupMemberEntity: GroupMemberEntity)

    @Update
    suspend fun update(groupMemberEntity: GroupMemberEntity)

    @Delete
    suspend fun delete(groupMemberEntity: GroupMemberEntity)

    @Query("SELECT * FROM GroupMemberEntity WHERE groupUuid = :groupUuid")
    fun getMembersFromGroup(groupUuid: String): Flow<List<GroupMemberEntity>>

    /**
     * Who this device records as the members of the groups the user is still in, see
     * [GroupEntity.isLeft][database.data.local.entity.GroupEntity.isLeft], each once, the user among
     * them, blocked ones aside, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity]: whoever may wake
     * the user with an expense of one of those groups, see `push.WakeList`.
     */
    @Query(
        """SELECT DISTINCT GroupMemberEntity.userUuid FROM GroupMemberEntity
        JOIN GroupEntity ON GroupEntity.uuid = GroupMemberEntity.groupUuid
        WHERE GroupEntity.isLeft = false
        AND GroupMemberEntity.userUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)""",
    )
    suspend fun getUnblockedCurrentMemberUuids(): List<String>
}