package database.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    // TODO: The same for group expenses.
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL ORDER BY timestamp DESC")
    fun pagingSource(): PagingSource<Int, ExpenseWithBeneficiariesEntity>

    @Insert
    suspend fun insert(expenseEntity: ExpenseEntity)

    @Insert
    suspend fun insertBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Transaction
    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        insert(expenseEntity = expenseWithBeneficiaries.expense)
        insertBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Update
    suspend fun update(expenseEntity: ExpenseEntity)

    @Upsert
    suspend fun updateBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Update
    @Transaction
    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        update(expenseEntity = expenseWithBeneficiaries.expense)
        updateBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Upsert
    suspend fun upsert(expenseEntity: ExpenseEntity)

    /** Insert-or-replace an expense together with its (fully replaced) beneficiaries. */
    @Transaction
    suspend fun upsertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        upsert(expenseEntity = expenseWithBeneficiaries.expense)
        deleteBeneficiariesFor(expenseUuid = expenseWithBeneficiaries.expense.uuid)
        insertBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Query("DELETE FROM BeneficiaryEntity WHERE expenseUuid = :expenseUuid")
    suspend fun deleteBeneficiariesFor(expenseUuid: String)

    @Query("DELETE FROM ExpenseEntity WHERE groupUuid IS NULL")
    suspend fun deleteAllOneTimeExpenses()

    @Query("DELETE FROM ExpenseEntity WHERE groupUuid IS NULL AND uuid NOT IN (:uuids)")
    suspend fun deleteOneTimeExpensesNotIn(uuids: List<String>)

    /**
     * Server-authoritative reconciliation of one-time expenses: drop any local
     * row (groupUuid IS NULL) absent from the server response, then upsert the
     * rest. Runs in a single transaction.
     */
    @Transaction
    suspend fun replaceAllOneTimeExpenses(expenses: List<ExpenseWithBeneficiariesEntity>) {
        val serverUuids = expenses.map { it.expense.uuid }
        if (serverUuids.isEmpty()) {
            deleteAllOneTimeExpenses()
        } else {
            deleteOneTimeExpensesNotIn(uuids = serverUuids)
        }
        expenses.forEach { upsertWithBeneficiaries(expenseWithBeneficiaries = it) }
    }

    @Delete
    suspend fun delete(expenseEntity: ExpenseEntity)

    @Query("DELETE FROM ExpenseEntity WHERE uuid = :expenseUuid")
    suspend fun hardDelete(expenseUuid: String)

    @Query("UPDATE ExpenseEntity SET isDeleted = true WHERE uuid = :expenseUuid")
    suspend fun delete(expenseUuid: String)

    @Query("UPDATE ExpenseEntity SET isDeleted = false WHERE uuid = :expenseUuid")
    suspend fun restore(expenseUuid: String)

    @Query("SELECT count(*) FROM ExpenseEntity WHERE isDeleted = false")
    suspend fun count(): Int

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid")
    fun getAllFromGroupAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid AND isDeleted = false")
    fun getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL")
    fun getAllWithoutGroupAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE isDeleted = false")
    fun getAllWithBeneficiariesAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    fun getWithBeneficiariesAsFlow(uuid: String): Flow<ExpenseWithBeneficiariesEntity?>
}