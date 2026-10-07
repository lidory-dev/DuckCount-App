package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-time expense this device can see but cannot open, and what it has done about it.
 *
 * The server tells every participant an expense exists; only its key tells them what it
 * says. A log-out takes the local MLS state with it, so a key message that arrives while
 * somebody is logged out can never be read, and the server's copy only helps for expenses
 * whose key had already arrived. What is left is to ask the creditor again, which is what
 * this row drives.
 *
 * It is kept apart from `ExpenseEntity` rather than being a half-filled row there: an
 * expense with a real title and an invented amount would be fed to the debt algorithm as
 * though it were the user's own.
 *
 * Nothing about it is shown to anyone. Waiting for a key is this code's problem, not the
 * user's: they shared an expense with somebody, and it turns up when it turns up. The row
 * exists to make that true, not to be reported.
 */
@Entity
class PendingExpenseKeyEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** Who to ask. The server's word, which is all there is before the expense opens. */
    val creditorUuid: String,

    /**
     * When this device first saw the expense without its key, which is the order the
     * expenses waiting are asked for in, see `PendingExpenseKeyDao.getInState`: a request
     * names the ones seen first, and creditors are asked in that order too, see
     * `OneTimeExpenseSync.requestMissingKeys`. It holds nothing back: the first request goes
     * out at once.
     */
    val firstSeenAt: Long,

    /**
     * When this device last tried asking, or null when it has not. A try counts whether the
     * request went out or the server offered no key package a channel to the creditor could
     * be built from.
     */
    val lastRequestedAt: Long?,

    /** How many times this device has tried asking, which the backoff between tries grows with. */
    val requestCount: Int = 0,

    val state: PendingExpenseKeyState = PendingExpenseKeyState.PENDING,
)

/** How far along the attempt to get a missing expense key has got. */
enum class PendingExpenseKeyState {
    /**
     * Still being asked for, which is where a row stays until the key arrives.
     *
     * There is no giving up after so many tries. Nothing tells the user an expense is
     * waiting, so giving up would mean an expense quietly never appearing, with nobody
     * able to ask why; a creditor who has not opened their app for a month is a reason to
     * keep asking slowly, not to stop. The backoff grows to a day and stays there.
     */
    PENDING,

    /**
     * The creditor's account is gone, so nobody holds the key any more.
     *
     * Reached only on the server saying so outright, never on a guess: an expense survives
     * its creditor deleting their account, so the row stays and stays unreadable. It is
     * the one thing asking again cannot fix.
     *
     * The server says so only to a message addressed to them. With no channel to the
     * creditor, the key-package lookup that comes first answers a deleted account just as
     * it answers one that has published nothing, and the row stays [PENDING].
     */
    CREDITOR_GONE,
}
