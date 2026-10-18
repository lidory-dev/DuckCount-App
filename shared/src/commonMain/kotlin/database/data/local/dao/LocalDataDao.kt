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
        deleteOutgoingContactRequests()
        deleteContacts()
        deleteDirectChannels()
        deleteIdentityConflicts()
        deleteOneTimeExpenseKeys()
        deleteUnverifiedExpenseKeys()
        deleteOneTimeExpenseRevisions()
        deleteOneTimeExpenseSettledMarks()
        deletePendingExpenseKeys()
        deleteKeyRequestBudgets()
        deletePendingProfileKeys()
        deleteProfileKeyRequestBudgets()
        deleteOwedExpenseKeys()
        deleteListedOneTimeExpenses()
        deleteChangeFeeds()
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

    @Query("DELETE FROM OutgoingContactRequestEntity")
    suspend fun deleteOutgoingContactRequests()

    @Query("DELETE FROM ContactEntity")
    suspend fun deleteContacts()

    @Query("DELETE FROM DirectChannelEntity")
    suspend fun deleteDirectChannels()

    @Query("DELETE FROM IdentityConflictEntity")
    suspend fun deleteIdentityConflicts()

    @Query("DELETE FROM OneTimeExpenseKeyEntity")
    suspend fun deleteOneTimeExpenseKeys()

    @Query("DELETE FROM UnverifiedExpenseKeyEntity")
    suspend fun deleteUnverifiedExpenseKeys()

    @Query("DELETE FROM OneTimeExpenseRevisionEntity")
    suspend fun deleteOneTimeExpenseRevisions()

    @Query("DELETE FROM OneTimeExpenseSettledMarkEntity")
    suspend fun deleteOneTimeExpenseSettledMarks()

    @Query("DELETE FROM PendingExpenseKeyEntity")
    suspend fun deletePendingExpenseKeys()

    @Query("DELETE FROM KeyRequestBudgetEntity")
    suspend fun deleteKeyRequestBudgets()

    @Query("DELETE FROM PendingProfileKeyEntity")
    suspend fun deletePendingProfileKeys()

    @Query("DELETE FROM ProfileKeyRequestBudgetEntity")
    suspend fun deleteProfileKeyRequestBudgets()

    @Query("DELETE FROM OwedExpenseKeyEntity")
    suspend fun deleteOwedExpenseKeys()

    @Query("DELETE FROM ListedOneTimeExpenseEntity")
    suspend fun deleteListedOneTimeExpenses()

    @Query("DELETE FROM ChangeFeedEntity")
    suspend fun deleteChangeFeeds()

    @Query("DELETE FROM MlsRecordEntity")
    suspend fun deleteMlsRecords()
}
