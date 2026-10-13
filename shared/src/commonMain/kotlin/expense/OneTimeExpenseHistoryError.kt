package expense

import util.Error
import util.NetworkError

/** Why the history of a one-time expense could not be read. */
sealed interface OneTimeExpenseHistoryError : Error {

    /**
     * This device shows no version of the expense, or holds no key it may open one with: none
     * that works, or one from a creditor who is blocked.
     */
    data object Unreadable : OneTimeExpenseHistoryError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : OneTimeExpenseHistoryError
}
