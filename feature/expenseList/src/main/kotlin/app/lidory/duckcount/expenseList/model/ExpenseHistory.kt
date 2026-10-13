package app.lidory.duckcount.expenseList.model

import androidx.annotation.StringRes
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as androidUiR

/** What reading the history of an expense gave. */
sealed interface ExpenseHistory {

    /** Its versions, the one shown first and the earliest last. */
    data class Versions(val versions: List<ExpenseVersion>) : ExpenseHistory

    /** Why there are none to show. */
    data class Unavailable(val error: ExpenseHistoryError) : ExpenseHistory
}

/**
 * Why the history of an expense could not be read, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level error onto this.
 */
enum class ExpenseHistoryError {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else: nothing on this device can open it, the server refused, … */
    UNKNOWN,
}

@StringRes
fun ExpenseHistoryError.messageRes(): Int = when (this) {
    ExpenseHistoryError.NO_INTERNET -> R.string.write_error_no_internet
    ExpenseHistoryError.TIMEOUT -> R.string.write_error_timeout
    ExpenseHistoryError.SERVER_UNREACHABLE -> R.string.write_error_server_unreachable
    ExpenseHistoryError.UNKNOWN -> androidUiR.string.an_error_occurred
}
