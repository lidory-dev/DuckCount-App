package database.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import database.data.local.dao.ExpenseDao
import database.data.mappers.toBeneficiaryEntity
import database.data.mappers.toExpenseEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.mappers.toOneTimeRequestBody
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseRemoteMediator
import database.domain.Beneficiary
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import database.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import util.NetworkError
import util.Result
import util.asEmptyDataResult
import util.onSuccess
import androidx.paging.map as pagingMap

class ExpenseRepositoryImpl(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
) : ExpenseRepository {

    @OptIn(ExperimentalPagingApi::class)
    override fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>> =
        Pager(
            config = PagingConfig(pageSize = ONE_TIME_PAGE_SIZE),
            remoteMediator = ExpenseRemoteMediator(
                expenseApi = expenseApi,
                expenseDao = expenseDao,
            ),
            pagingSourceFactory = { expenseDao.pagingSource() },
        ).flow.map { pagingData ->
            pagingData.pagingMap { it.toExpenseWithBeneficiaries() }
        }

    override suspend fun createOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): Result<Unit, NetworkError> =
        expenseApi.createOneTimeExpense(body = expenseWithBeneficiaries.toOneTimeRequestBody())
            .onSuccess { serverUuid ->
                // The server owns the uuid; cache the row under it.
                val synced = expenseWithBeneficiaries.copy(
                    uuid = serverUuid,
                    beneficiaries = expenseWithBeneficiaries.beneficiaries.map {
                        it.copy(expenseUuid = serverUuid)
                    },
                )
                expenseDao.insertWithBeneficiaries(
                    expenseWithBeneficiaries = synced.toExpenseWithBeneficiariesEntity(),
                )
            }
            .asEmptyDataResult()

    override suspend fun updateOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): Result<Unit, NetworkError> =
        expenseApi.updateOneTimeExpense(
            uuid = expenseWithBeneficiaries.uuid,
            body = expenseWithBeneficiaries.toOneTimeRequestBody(),
        ).onSuccess {
            expenseDao.upsertWithBeneficiaries(
                expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity(),
            )
        }.asEmptyDataResult()

    override suspend fun deleteOneTimeExpense(uuid: String): Result<Unit, NetworkError> =
        expenseApi.deleteOneTimeExpense(uuid = uuid)
            .onSuccess { expenseDao.hardDelete(expenseUuid = uuid) }
            .asEmptyDataResult()

    override suspend fun insert(expense: Expense) {
        expenseDao.insert(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun insertBeneficiaries(beneficiaries: List<Beneficiary>) {
        expenseDao.insertBeneficiaries(beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() })
    }

    override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.insertWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    override suspend fun update(expense: Expense) {
        expenseDao.update(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.updateWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    override suspend fun delete(expense: Expense) {
        expenseDao.delete(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun delete(expenseUuid: String) {
        expenseDao.delete(expenseUuid)
    }

    override suspend fun restore(expenseUuid: String) {
        expenseDao.restore(expenseUuid)
    }

    override fun getAll(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithBeneficiariesAsFlow().map { expensesWithBeneficiariesEntities ->
            expensesWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllFromGroupWithoutDeletedOnes(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithoutGroupAsFlow().map { expenseWithBeneficiariesEntities ->
            expenseWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseDao.getWithBeneficiariesAsFlow(uuid = uuid).map { expenseWithBeneficiariesEntity ->
            expenseWithBeneficiariesEntity?.toExpenseWithBeneficiaries()
        }
    }

    private companion object {
        // Server returns the full list at once; this only sizes local paging windows.
        const val ONE_TIME_PAGE_SIZE = 20
    }
}
