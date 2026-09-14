package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import database.data.local.entity.MlsRecordEntity

/**
 * The key-value operations `lib-duckcount` asks of its MLS store, one per method.
 *
 * Keys are only unique within a namespace, so every query is scoped to one.
 */
@Dao
interface MlsRecordDao {
    @Query("SELECT `value` FROM MlsRecordEntity WHERE namespace = :namespace AND `key` = :key")
    suspend fun get(namespace: String, key: ByteArray): ByteArray?

    @Upsert
    suspend fun put(record: MlsRecordEntity)

    @Query("DELETE FROM MlsRecordEntity WHERE namespace = :namespace AND `key` = :key")
    suspend fun delete(namespace: String, key: ByteArray)

    @Query("SELECT `key` FROM MlsRecordEntity WHERE namespace = :namespace")
    suspend fun keys(namespace: String): List<ByteArray>
}
