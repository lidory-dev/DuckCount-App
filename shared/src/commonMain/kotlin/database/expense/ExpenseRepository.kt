package database.expense

import database.beneficiary.Beneficiary
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {

    suspend fun insert(expense: Expense)

    suspend fun insertBeneficiaries(beneficiaries: List<Beneficiary>)

    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    suspend fun update(expense: Expense)

    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    suspend fun delete(expense: Expense)

    suspend fun deleteFromUuid(expenseUuid: String)

    fun getAll(): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>>

    fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>
}
