package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The newest signed version of an expense this device has shown or signed, a one-time expense's,
 * see `expense.OneTimeExpenseSealing`, or a group's, see `group.GroupExpenseSealing`. Nothing
 * older is shown after it, and for a group expense nothing at the same revision either.
 *
 * Apart from the expense's row on purpose: a sync deletes the row of a one-time expense whenever
 * the server stops listing it, and a mark that went with it would let the server leave an expense
 * out once, then serve a version from before an edit. A group expense's row stays, nothing removing
 * a single one, and the two kinds keep the one table, as they keep the one table of rows: no row is
 * stored over one of the other kind, see `database.data.local.dao.ExpenseDao.storeGroupExpenses`.
 */
@Entity
class ExpenseRevisionEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,
    val newestRevision: Long,
)
