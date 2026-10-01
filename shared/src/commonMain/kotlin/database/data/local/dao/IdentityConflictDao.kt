package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import database.data.local.entity.IdentityConflictEntity

@Dao
interface IdentityConflictDao {
    /** Records the conflict, which the client meets again every time it opens. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conflict: IdentityConflictEntity)

    /** Whether [peerUuid] is blocked, see [IdentityConflictEntity]. */
    @Query("SELECT EXISTS(SELECT 1 FROM IdentityConflictEntity WHERE peerUuid = :peerUuid)")
    suspend fun isBlocked(peerUuid: String): Boolean

    /** Every blocked peer, for checking a whole list in one read. */
    @Query("SELECT peerUuid FROM IdentityConflictEntity")
    suspend fun getBlocked(): List<String>
}
