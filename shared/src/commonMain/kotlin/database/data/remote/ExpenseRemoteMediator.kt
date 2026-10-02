package database.data.remote

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import expense.OneTimeExpenseSync
import util.OneTimeExpenseSyncException
import util.Result

/**
 * Drives the local one-time-expense cache from the server.
 *
 * The work is [OneTimeExpenseSync]'s: reading the list, opening what there are keys for,
 * writing down the rest as waiting, asking for those keys, and handing over the ones the
 * user owes. This only says when, which is once, as the list opens: there is no server-side
 * paging, so a refresh is all there is.
 *
 * TODO: Add pagination on the server.
 */
@OptIn(ExperimentalPagingApi::class)
class ExpenseRemoteMediator(
    private val oneTimeExpenseSync: OneTimeExpenseSync,
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

        val refreshed = oneTimeExpenseSync.refresh()

        if (refreshed is Result.Error) {
            return MediatorResult.Error(OneTimeExpenseSyncException(error = refreshed.error))
        }

        // Only now, because asking is about what the read just found: the expenses this
        // device can see and cannot open. Asking first, which is what the screen used to
        // do, looked at an empty table and found nothing to ask about.
        //
        // The same goes for handing over the keys the user owes, which this read may just
        // have shown are due: the expenses that name the people they are owed to.
        //
        // Neither's failure is this load's failure. The expenses are read and on screen; a
        // message that did not go out is picked up by the next sync, and turning that into
        // a failed load would put an error over a list that is perfectly fine.
        oneTimeExpenseSync.shareOwedKeys()
        oneTimeExpenseSync.requestMissingKeys()

        return MediatorResult.Success(endOfPaginationReached = true)
    }
}
