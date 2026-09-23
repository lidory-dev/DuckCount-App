package app.lidory.duckcount.expenseList.model

import androidx.annotation.StringRes
import app.lidory.duckcount.expenseList.R

/**
 * Why a create/update/delete write failed, at a granularity the UI can explain.
 * The composeApp callback impls map the low-level networking error onto this.
 */
enum class ExpenseWriteError {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** The server rejected the request contents (e.g. 400/422 invalid beneficiaries). */
    INVALID_DATA,

    /** The maximum number of edits has been reached. */
    CONFLICT,

    /** The session is no longer valid (401). */
    UNAUTHORIZED,

    /**
     * This device does not hold the key to the expense being edited.
     *
     * Re-sealing it under a new key would leave everyone it is shared with holding one
     * that no longer opens it, so the edit is refused. It clears itself once the key
     * arrives.
     */
    KEY_MISSING,

    /** Anything else (server error, unexpected response, …). */
    UNKNOWN,
}

@StringRes
fun ExpenseWriteError.messageRes(): Int = when (this) {
    ExpenseWriteError.NO_INTERNET -> R.string.write_error_no_internet
    ExpenseWriteError.TIMEOUT -> R.string.write_error_timeout
    ExpenseWriteError.SERVER_UNREACHABLE -> R.string.write_error_server_unreachable
    ExpenseWriteError.INVALID_DATA -> R.string.write_error_invalid_data
    ExpenseWriteError.CONFLICT -> R.string.write_error_conflict
    ExpenseWriteError.UNAUTHORIZED -> R.string.write_error_unauthorized
    ExpenseWriteError.KEY_MISSING -> R.string.write_error_key_missing
    ExpenseWriteError.UNKNOWN -> R.string.write_error_unknown
}
