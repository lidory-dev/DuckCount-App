package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.ContactRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRequestDao {
    /**
     * How many requests are waiting.
     */
    @Query("SELECT COUNT(*) FROM ContactRequestEntity")
    fun getCountAsFlow(): Flow<Int>

    @Upsert
    suspend fun upsert(contactRequests: List<ContactRequestEntity>)

    @Query("DELETE FROM ContactRequestEntity")
    suspend fun deleteAll()

    @Query("DELETE FROM ContactRequestEntity WHERE uuid NOT IN (:uuids)")
    suspend fun deleteNotIn(uuids: List<String>)

    /**
     * Server-authoritative reconciliation: drop any local row the server no longer
     * lists, then upsert the rest. Runs in a single transaction.
     *
     * Deleting only what is missing, rather than emptying the table first, is what
     * keeps the count from flickering through zero on every refresh.
     */
    @Transaction
    suspend fun replaceAll(contactRequests: List<ContactRequestEntity>) {
        val serverUuids = contactRequests.map { it.uuid }
        if (serverUuids.isEmpty()) {
            deleteAll()
        } else {
            deleteNotIn(uuids = serverUuids)
        }
        upsert(contactRequests = contactRequests)
    }
}
