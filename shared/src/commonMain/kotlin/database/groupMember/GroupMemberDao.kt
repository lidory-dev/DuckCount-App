package database.groupMember

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update

@Dao
interface GroupMemberDao {
    @Insert
    suspend fun insert(groupMemberEntity: GroupMemberEntity)

    @Update
    suspend fun update(groupMemberEntity: GroupMemberEntity)

    @Delete
    suspend fun delete(groupMemberEntity: GroupMemberEntity)

}