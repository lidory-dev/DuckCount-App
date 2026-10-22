package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.PendingProfileKeyEntity
import database.data.local.entity.ProfileKeyRequestBudgetEntity

@Dao
interface PendingProfileKeyDao {
    /**
     * Records that the key [profileEncryptionKey] no longer opens the latest version of the
     * profile of the contact [userUuid], leaving alone what this device already tried, see
     * [PendingProfileKeyEntity].
     *
     * Deliberately not an upsert of whole rows, as for `PendingExpenseKeyDao.insertNew`: a read
     * that overwrote what was tried would put the backoff back to zero.
     */
    @Transaction
    suspend fun recordStale(userUuid: String, profileEncryptionKey: ByteArray) {
        insertNew(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey)
        setReadWith(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey)
    }

    @Query(
        """INSERT OR IGNORE INTO PendingProfileKeyEntity
        (userUuid, profileEncryptionKey, lastRequestedAt, requestCount)
        VALUES (:userUuid, :profileEncryptionKey, NULL, 0)""",
    )
    suspend fun insertNew(userUuid: String, profileEncryptionKey: ByteArray)

    /** See [PendingProfileKeyEntity.profileEncryptionKey]. */
    @Query("UPDATE PendingProfileKeyEntity SET profileEncryptionKey = :profileEncryptionKey WHERE userUuid = :userUuid")
    suspend fun setReadWith(userUuid: String, profileEncryptionKey: ByteArray)

    /**
     * The rows still to be asked about, of the user's contacts, removed and blocked ones aside,
     * see [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity]. Those never
     * asked come first, then the ones asked longest ago.
     */
    @Query(
        """SELECT * FROM PendingProfileKeyEntity
        WHERE userUuid IN (SELECT userUuid FROM ContactEntity WHERE isRemoved = false)
        AND userUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)
        ORDER BY lastRequestedAt IS NOT NULL, lastRequestedAt, userUuid""",
    )
    suspend fun getAskable(): List<PendingProfileKeyEntity>

    @Query(
        """UPDATE PendingProfileKeyEntity
        SET lastRequestedAt = :requestedAt, requestCount = requestCount + 1
        WHERE userUuid = :userUuid""",
    )
    suspend fun markRequested(userUuid: String, requestedAt: Long)

    /** Drops the row of [userUuid], once a read opens their latest version or they stop being a contact. */
    @Query("DELETE FROM PendingProfileKeyEntity WHERE userUuid = :userUuid")
    suspend fun delete(userUuid: String)

    /** When the budget of profile key requests to [peerUuid] is whole again, or null when none was ever spent. */
    @Query("SELECT budgetFullAt FROM ProfileKeyRequestBudgetEntity WHERE peerUuid = :peerUuid")
    suspend fun getRequestBudgetFullAt(peerUuid: String): Long?

    @Upsert
    suspend fun setRequestBudget(budget: ProfileKeyRequestBudgetEntity)
}
