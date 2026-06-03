package database.groupMember

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

}