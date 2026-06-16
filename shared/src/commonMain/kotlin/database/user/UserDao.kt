package database.user

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert
    suspend fun insert(userEntity: UserEntity)

    @Update
    suspend fun update(userEntity: UserEntity)

    @Delete
    suspend fun delete(userEntity: UserEntity)

    @Query("SELECT * FROM UserEntity")
    fun getAllAsFlow(): Flow<List<UserEntity>>

    @Query("SELECT * FROM UserEntity WHERE fullName LIKE '%' ||:filter|| '%'")
    fun getAllFiltered(filter: String): Flow<List<UserEntity>>

    @Transaction
    @Query("SELECT * FROM UserEntity WHERE uuid = :uuid")
    fun getUserAsFlow(uuid: String): Flow<UserEntity>

    @Query("SELECT fullName FROM UserEntity WHERE uuid = :uuid")
    fun getUserFullNameAsFlow(uuid: String): Flow<String?>

    @Query(
        """SELECT UserEntity.* 
        FROM UserEntity 
        INNER JOIN GroupMemberEntity ON UserEntity.uuid = GroupMemberEntity.userUuid 
        WHERE UserEntity.fullName LIKE '%' ||:filter|| '%' 
        AND GroupMemberEntity.groupUuid = :groupUuid""",
    )
    fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<UserEntity>>
}
