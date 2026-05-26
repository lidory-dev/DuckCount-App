package database.group

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import database.groupMember.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert
    suspend fun insert(groupEntity: GroupEntity)

    @Insert
    suspend fun insertMembers(groupMemberEntities: List<GroupMemberEntity>)

    @Transaction
    suspend fun insertWithMembers(groupWithMembersEntity: GroupWithMembersEntity) {
        insert(groupEntity = groupWithMembersEntity.group)
        insertMembers(groupMemberEntities = groupWithMembersEntity.groupMembers)
    }

    @Update
    suspend fun update(groupEntity: GroupEntity)

    @Delete
    suspend fun delete(groupEntity: GroupEntity)

    @Query("SELECT count(*) FROM GroupEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM GroupEntity")
    fun getAllAsFlow(): Flow<List<GroupEntity>>
}