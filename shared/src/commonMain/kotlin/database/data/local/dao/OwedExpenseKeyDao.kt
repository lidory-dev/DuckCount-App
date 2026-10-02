package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import database.data.local.entity.OwedExpenseKeyEntity

@Dao
interface OwedExpenseKeyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(keys: List<OwedExpenseKeyEntity>)

    /**
     * The keys owed to somebody the expense, as this device shows it, names: the ones whose
     * write has landed, and that can go out.
     *
     * Against the expense row as well as its beneficiaries, since a sync that drops an
     * expense the server stopped listing leaves its beneficiary rows behind.
     */
    @Query(
        """SELECT owed.* FROM OwedExpenseKeyEntity owed
        JOIN ExpenseEntity expense ON expense.uuid = owed.expenseUuid
        JOIN BeneficiaryEntity beneficiary
            ON beneficiary.expenseUuid = owed.expenseUuid AND beneficiary.userUuid = owed.recipientUuid
        ORDER BY owed.recipientUuid, owed.expenseUuid""",
    )
    suspend fun getListed(): List<OwedExpenseKeyEntity>

    @Query("DELETE FROM OwedExpenseKeyEntity WHERE recipientUuid = :recipientUuid AND expenseUuid IN (:expenseUuids)")
    suspend fun delete(recipientUuid: String, expenseUuids: List<String>)

    /** Drops what is owed on [expenseUuid] to anybody but [recipientUuids]. */
    @Query("DELETE FROM OwedExpenseKeyEntity WHERE expenseUuid = :expenseUuid AND recipientUuid NOT IN (:recipientUuids)")
    suspend fun deleteNotIn(expenseUuid: String, recipientUuids: List<String>)

    @Query("DELETE FROM OwedExpenseKeyEntity WHERE expenseUuid = :expenseUuid")
    suspend fun deleteFor(expenseUuid: String)
}
