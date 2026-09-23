package util

import expense.OneTimeExpenseSyncError

/**
 * A failed one-time-expense sync, as an exception, because Paging's `RemoteMediator` reports
 * failures that way and the rest of this codebase does not.
 */
class OneTimeExpenseSyncException(
    val error: OneTimeExpenseSyncError,
    cause: Throwable? = null,
) : Exception(error::class.simpleName, cause)
