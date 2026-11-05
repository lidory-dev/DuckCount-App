package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import database.data.local.entity.BlockedUserEntity
import kotlinx.coroutines.flow.Flow

/** The users the user blocked, see [BlockedUserEntity]. */
@Dao
interface BlockedUserDao {
    /**
     * Writes [blocked] in place of whatever row there was for them, a tombstone included, so that
     * blocking somebody again whom the user just unblocked blocks them, and the next backup writes
     * them to the vault again.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blocked: BlockedUserEntity)

    /** Adds the users the vault holds that this device does not, leaving the others as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(blocked: List<BlockedUserEntity>)

    /** Lifts the block on [userUuid], leaving a tombstone for the next backup, see [BlockedUserEntity.isUnblocked]. */
    @Query("UPDATE BlockedUserEntity SET isUnblocked = 1, isBackedUp = 0 WHERE userUuid = :userUuid AND isUnblocked = 0")
    suspend fun unblock(userUuid: String)

    /** Whether the user blocked [userUuid], and has not unblocked them since. */
    @Query("SELECT EXISTS(SELECT 1 FROM BlockedUserEntity WHERE userUuid = :userUuid AND isUnblocked = 0)")
    suspend fun isBlocked(userUuid: String): Boolean

    /** Everybody the user blocked, for checking a whole list in one read. */
    @Query("SELECT userUuid FROM BlockedUserEntity WHERE isUnblocked = 0")
    suspend fun getBlockedUuids(): List<String>

    /** Everybody the user blocked, as it changes. */
    @Query("SELECT * FROM BlockedUserEntity WHERE isUnblocked = 0")
    fun getBlockedAsFlow(): Flow<List<BlockedUserEntity>>

    /** The rows the vault does not hold as they are, tombstones included. */
    @Query("SELECT * FROM BlockedUserEntity WHERE isBackedUp = 0")
    suspend fun getNotBackedUp(): List<BlockedUserEntity>

    /**
     * Marks the block on [userUuid] backed up, only if it still is under [fullName]: unblocked
     * during the write, or blocked again under another name, it stays for the next backup.
     */
    @Query(
        """UPDATE BlockedUserEntity SET isBackedUp = 1
        WHERE userUuid = :userUuid AND isUnblocked = 0 AND fullName IS :fullName""",
    )
    suspend fun markBackedUp(userUuid: String, fullName: String?)

    /** Drops the tombstones of [userUuids], only those still unblocked: one blocked again stays. */
    @Query("DELETE FROM BlockedUserEntity WHERE userUuid IN (:userUuids) AND isUnblocked = 1")
    suspend fun deleteUnblocked(userUuids: List<String>)
}
