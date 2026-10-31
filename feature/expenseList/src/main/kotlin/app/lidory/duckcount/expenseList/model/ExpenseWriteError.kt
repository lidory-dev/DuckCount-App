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

    /** The maximum number of edits of a one-time expense has been reached. */
    CONFLICT,

    /**
     * Another member of the group changed the expense first, which the device shows from then on:
     * the write was made over the version it replaced, and nothing of it was taken.
     */
    CHANGED_MEANWHILE,

    /** The session is no longer valid (401). */
    UNAUTHORIZED,

    /**
     * The expense is one of a group the user is no longer a member of, removed or left, which stays
     * read-only: the screen can still be open as a sync reads the removal, and the server can say
     * so before one does.
     */
    GROUP_LEFT,

    /**
     * The expense is one of a group whose creation the server has yet to take, which nothing is
     * written in until it has: it clears itself once the creation goes through.
     */
    CREATION_PENDING,

    /**
     * The expense is one of a group that waits for its owner: a member's change to it is one no
     * device could apply, and this device can write nothing the server would take until the
     * owner's device has set it right, which it does as soon as it is online.
     */
    AWAITING_OWNER,

    /** The server's limit on the group expenses one user writes is reached for now. */
    RATE_LIMITED,

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
    ExpenseWriteError.CHANGED_MEANWHILE -> R.string.write_error_changed_meanwhile
    ExpenseWriteError.UNAUTHORIZED -> R.string.write_error_unauthorized
    ExpenseWriteError.GROUP_LEFT -> R.string.write_error_group_left
    ExpenseWriteError.CREATION_PENDING -> R.string.write_error_creation_pending
    ExpenseWriteError.AWAITING_OWNER -> R.string.write_error_awaiting_owner
    ExpenseWriteError.RATE_LIMITED -> R.string.write_error_rate_limited
    ExpenseWriteError.KEY_MISSING -> R.string.write_error_key_missing
    ExpenseWriteError.UNKNOWN -> R.string.write_error_unknown
}
