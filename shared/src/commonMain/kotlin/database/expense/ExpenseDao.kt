package database.expense

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(expenseEntity: ExpenseEntity)

    @Update
    fun update(expenseEntity: ExpenseEntity)

    @Delete
    fun delete(expenseEntity: ExpenseEntity)

    @Query("SELECT count(*) FROM ExpenseEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM ExpenseEntity")
    fun getAllAsFlow(): Flow<List<ExpenseEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    fun getWithCurrencyAndBeneficiariesAsFlow(uuid: String): Flow<ExpenseWithBeneficiariesEntity>
}
