package database.expense

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import database.beneficiary.BeneficiaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
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

    @Delete
    suspend fun delete(expenseEntity: ExpenseEntity)

    @Query("SELECT count(*) FROM ExpenseEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM ExpenseEntity")
    fun getAllAsFlow(): Flow<List<ExpenseEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid")
    fun getAllFromGroupAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL")
    fun getAllWithoutGroupAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity")
    fun getAllWithBeneficiariesAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    fun getWithBeneficiariesAsFlow(uuid: String): Flow<ExpenseWithBeneficiariesEntity>
}
