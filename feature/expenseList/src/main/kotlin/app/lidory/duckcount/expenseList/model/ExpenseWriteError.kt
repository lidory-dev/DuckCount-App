package app.lidory.duckcount.expenseList.model

import androidx.annotation.StringRes
import app.lidory.duckcount.expenseList.R

/**
 * Why a create/update/delete write failed, at a granularity the UI can explain.
 * The composeApp callback impls map the low-level networking error onto this.
 */
enum class ExpenseWriteError {
    /** No connectivity / timed out. */
    NO_INTERNET,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** The server rejected the request contents (e.g. 400/422 invalid beneficiaries). */
    INVALID_DATA,

    /** The maximum number of edits has been reached. */
    CONFLICT,

    /** The session is no longer valid (401). */
    UNAUTHORIZED,

    /** Anything else (server error, unexpected response, …). */
    UNKNOWN,
}

@StringRes
fun ExpenseWriteError.messageRes(): Int = when (this) {
    ExpenseWriteError.NO_INTERNET -> R.string.write_error_no_internet
    ExpenseWriteError.SERVER_UNREACHABLE -> R.string.write_error_server_unreachable
    ExpenseWriteError.INVALID_DATA -> R.string.write_error_invalid_data
    ExpenseWriteError.CONFLICT -> R.string.write_error_conflict
    ExpenseWriteError.UNAUTHORIZED -> R.string.write_error_unauthorized
    ExpenseWriteError.UNKNOWN -> R.string.write_error_unknown
}
