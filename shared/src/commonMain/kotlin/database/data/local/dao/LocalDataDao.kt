package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Empties the database once the account it was filled for is no longer the one signed in, so
 * whoever signs in next on this device starts from nothing.
 */
@Dao
interface LocalDataDao {
    /**
     * Deletes every row of every table, in one transaction.
     *
     * One DELETE per entity in `AppDatabase`, kept by hand: Room's `clearAllTables` would find the
     * tables on its own, but it only exists on Android. A table missing here outlives the log-out.
     */
    @Transaction
    suspend fun deleteAll() {
        deleteUsers()
        deleteExpenses()
        deleteCurrencies()
        deleteBeneficiaries()
        deleteGroups()
        deleteGroupMembers()
        deleteContactRequests()
        deleteMlsRecords()
    }

    @Query("DELETE FROM UserEntity")
    suspend fun deleteUsers()

    @Query("DELETE FROM ExpenseEntity")
    suspend fun deleteExpenses()

    @Query("DELETE FROM CurrencyEntity")
    suspend fun deleteCurrencies()

    @Query("DELETE FROM BeneficiaryEntity")
    suspend fun deleteBeneficiaries()

    @Query("DELETE FROM GroupEntity")
    suspend fun deleteGroups()

    @Query("DELETE FROM GroupMemberEntity")
    suspend fun deleteGroupMembers()

    @Query("DELETE FROM ContactRequestEntity")
    suspend fun deleteContactRequests()

    @Query("DELETE FROM MlsRecordEntity")
    suspend fun deleteMlsRecords()
}
