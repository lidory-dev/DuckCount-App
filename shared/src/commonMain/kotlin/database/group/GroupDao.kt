package database.group

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert
    suspend fun insert(groupEntity: GroupEntity)

    @Update
    suspend fun update(groupEntity: GroupEntity)

    @Delete
    suspend fun delete(groupEntity: GroupEntity)

    @Query("SELECT count(*) FROM GroupEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM GroupEntity")
    fun getAllAsFlow(): Flow<List<GroupEntity>>
}