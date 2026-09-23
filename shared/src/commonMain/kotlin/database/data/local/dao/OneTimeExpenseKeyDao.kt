package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import database.data.local.entity.OneTimeExpenseKeyEntity

@Dao
interface OneTimeExpenseKeyDao {
    @Upsert
    suspend fun upsert(key: OneTimeExpenseKeyEntity)

    @Upsert
    suspend fun upsertAll(keys: List<OneTimeExpenseKeyEntity>)

    @Query("SELECT * FROM OneTimeExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun get(expenseUuid: String): OneTimeExpenseKeyEntity?

    /** Every key this device holds, which is what opening a whole expense list needs. */
    @Query("SELECT * FROM OneTimeExpenseKeyEntity")
    suspend fun getAll(): List<OneTimeExpenseKeyEntity>

    /** The verified keys the vault does not hold yet. */
    @Query("SELECT * FROM OneTimeExpenseKeyEntity WHERE isVerified = true AND isBackedUp = false")
    suspend fun getNotBackedUp(): List<OneTimeExpenseKeyEntity>

    @Query("UPDATE OneTimeExpenseKeyEntity SET isBackedUp = true WHERE expenseUuid IN (:expenseUuids)")
    suspend fun markBackedUp(expenseUuids: List<String>)

    @Query("DELETE FROM OneTimeExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun delete(expenseUuid: String)
}
