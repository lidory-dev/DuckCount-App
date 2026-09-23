package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The newest signed version of a one-time expense this device has shown or signed, see
 * `expense.OneTimeExpenseSealing`. Nothing older is shown after it.
 *
 * Apart from the expense's row on purpose: a sync deletes the row whenever the server stops
 * listing the expense, and a mark that went with it would let the server leave an expense
 * out once, then serve a version from before an edit.
 */
@Entity
class OneTimeExpenseRevisionEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,
    val newestRevision: Long,
)
