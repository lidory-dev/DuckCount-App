package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-time expense the server lists the user on, as it last sent it: [json] is the
 * listing's entry for it exactly as written, nothing in it opened, an `ExpenseDto` to
 * this build.
 *
 * Together they are the server's listing, kept on the device so that a sync need not
 * read it again: read whole once, then brought up to date with what changed, see
 * [ChangeFeedEntity]. What a sync does with a listing it does with this, see
 * `expense.OneTimeExpenseSync.refresh`, including opening an expense whose key only
 * arrived since, which the server will not send again until it changes.
 *
 * Kept as the server wrote it rather than as this build reads it: a newer build may read
 * more of what the server wrote, and that must still be there. What the server only starts
 * writing later reaches it when the server starts the feed over.
 */
@Entity
class ListedOneTimeExpenseEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,
    val json: String,
)
