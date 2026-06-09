package database.expense

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
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

    @Upsert
    suspend fun updateBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Update
    @Transaction
    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        update(expenseEntity = expenseWithBeneficiaries.expense)
        updateBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Delete
    suspend fun delete(expenseEntity: ExpenseEntity)

    @Query("UPDATE ExpenseEntity SET isActive = false WHERE uuid = :expenseUuid")
    suspend fun deleteByUuid(expenseUuid: String)

    @Query("UPDATE ExpenseEntity SET isActive = true WHERE uuid = :expenseUuid")
    suspend fun enableByUuid(expenseUuid: String)

    @Query("SELECT count(*) FROM ExpenseEntity WHERE isActive = true")
    suspend fun count(): Int

    @Query("SELECT * FROM ExpenseEntity WHERE isActive = true")
    fun getAllAsFlow(): Flow<List<ExpenseEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid")
    fun getAllFromGroupAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid AND isActive = true")
    fun getAllActiveFromGroupAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL")
    fun getAllWithoutGroupAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE isActive = true")
    fun getAllWithBeneficiariesAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    fun getWithBeneficiariesAsFlow(uuid: String): Flow<ExpenseWithBeneficiariesEntity?>
}
