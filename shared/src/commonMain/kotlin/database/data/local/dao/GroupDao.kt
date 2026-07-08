package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupWithMembersEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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

    @Upsert
    suspend fun updateMembers(members: List<GroupMemberEntity>)

    @Update
    @Transaction
    suspend fun updateWithMembers(groupWithMembers: GroupWithMembersEntity) {
        update(groupWithMembers.group)

        val oldUser = getWithMembers(groupWithMembers.group.uuid).first().groupMembers
        val userToRemove = oldUser - groupWithMembers.groupMembers.toSet()

        updateMembers(groupWithMembers.groupMembers)
        deleteMembers(groupMembers = userToRemove)
    }

    @Query("DELETE FROM GroupMemberEntity WHERE groupUuid = :groupUuid AND userUuid = :userUuid")
    suspend fun removeGroupMember(userUuid: String, groupUuid: String)

    @Delete
    suspend fun delete(groupEntity: GroupEntity)

    @Delete
    suspend fun deleteMembers(groupMembers: List<GroupMemberEntity>)

    @Query("SELECT count(*) FROM GroupEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM GroupEntity")
    fun getAllAsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getAsFlow(uuid: String): Flow<GroupEntity>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getWithMembers(uuid: String): Flow<GroupWithMembersEntity>
}