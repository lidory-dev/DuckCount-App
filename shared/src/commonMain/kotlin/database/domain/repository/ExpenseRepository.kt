package database.domain.repository

import androidx.paging.PagingData
import database.domain.Beneficiary
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import kotlinx.coroutines.flow.Flow
import util.NetworkError
import util.Result

interface ExpenseRepository {

    /**
     * Paged one-time expenses (`groupUuid == null`), backed by Room and kept in
     * sync with the server by [database.data.remote.ExpenseRemoteMediator].
     */
    fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>>

    /** Online-first write-through: POST to the server, then cache locally. */
    suspend fun createOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): Result<Unit, NetworkError>

    suspend fun updateOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): Result<Unit, NetworkError>

    suspend fun deleteOneTimeExpense(uuid: String): Result<Unit, NetworkError>

    suspend fun insert(expense: Expense)

    suspend fun insertBeneficiaries(beneficiaries: List<Beneficiary>)

    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    suspend fun update(expense: Expense)

    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    suspend fun delete(expense: Expense)

    suspend fun delete(expenseUuid: String)

    suspend fun restore(expenseUuid: String)

    fun getAll(): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllFromGroupWithoutDeletedOnes(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>>

    fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>
}
