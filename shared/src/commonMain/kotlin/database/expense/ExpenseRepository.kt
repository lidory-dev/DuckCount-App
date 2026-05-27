package database.expense

import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {

    suspend fun insert(expense: Expense)

    suspend fun update(expense: Expense)

    suspend fun delete(expense: Expense)

    fun getAll(): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>>

    fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries>
}
