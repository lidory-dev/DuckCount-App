package database.data.remote

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import database.data.local.dao.ExpenseDao
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.mappers.toExpenseWithBeneficiariesEntity
import util.NetworkException
import util.Result

/**
 * Drives the local one-time-expense cache from the server.
 *
 * TODO: Add pagination on the server.
 */
@OptIn(ExperimentalPagingApi::class)
class ExpenseRemoteMediator(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
) : RemoteMediator<Int, ExpenseWithBeneficiariesEntity>() {

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, ExpenseWithBeneficiariesEntity>,
    ): MediatorResult {
        // TODO: No server-side paging: only a REFRESH does any work.
        if (loadType != LoadType.REFRESH) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }

        return when (val result = expenseApi.getOneTimeExpenses()) {
            is Result.Error -> MediatorResult.Error(NetworkException(error = result.error))
            is Result.Success -> {
                expenseDao.replaceAllOneTimeExpenses(
                    expenses = result.data.map { it.toExpenseWithBeneficiariesEntity() },
                )
                MediatorResult.Success(endOfPaginationReached = true)
            }
        }
    }
}
