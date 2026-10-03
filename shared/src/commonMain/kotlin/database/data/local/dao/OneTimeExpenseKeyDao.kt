package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.OneTimeExpenseKeyEntity
import database.data.local.entity.UnverifiedExpenseKeyEntity

@Dao
interface OneTimeExpenseKeyDao {
    @Upsert
    suspend fun upsertAll(keys: List<OneTimeExpenseKeyEntity>)

    /**
     * Stores [keys], and drops every key held unverified to their expenses: a key to an
     * expense that has one that works is never tried again.
     */
    @Transaction
    suspend fun upsertVerified(keys: List<OneTimeExpenseKeyEntity>) {
        upsertAll(keys = keys)

        keys.forEach { deleteUnverifiedFor(expenseUuid = it.expenseUuid) }
    }

    @Query("SELECT * FROM OneTimeExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun get(expenseUuid: String): OneTimeExpenseKeyEntity?

    /** Every key this device holds as working, read whole since a whole expense list needs them. */
    @Query("SELECT * FROM OneTimeExpenseKeyEntity")
    suspend fun getAll(): List<OneTimeExpenseKeyEntity>

    @Query("DELETE FROM OneTimeExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun delete(expenseUuid: String)

    @Upsert
    suspend fun upsertUnverified(key: UnverifiedExpenseKeyEntity)

    /**
     * Stores [key], unless this device holds a key to its expense that works. In one
     * transaction, so that it cannot land just after one has been verified.
     */
    @Transaction
    suspend fun upsertUnverifiedUnlessVerified(key: UnverifiedExpenseKeyEntity) {
        if (get(expenseUuid = key.expenseUuid) != null) return

        upsertUnverified(key = key)
    }

    @Query("SELECT * FROM UnverifiedExpenseKeyEntity")
    suspend fun getAllUnverified(): List<UnverifiedExpenseKeyEntity>

    @Query("SELECT expenseUuid FROM UnverifiedExpenseKeyEntity")
    suspend fun getUnverifiedExpenseUuids(): List<String>

    /** One statement per expense, in one transaction, so there is no limit on how many. */
    @Transaction
    suspend fun deleteUnverified(expenseUuids: List<String>) {
        expenseUuids.forEach { deleteUnverifiedFor(expenseUuid = it) }
    }

    @Query("DELETE FROM UnverifiedExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun deleteUnverifiedFor(expenseUuid: String)
}
