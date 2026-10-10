package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where this device has got to in one of the user's feeds of changes: [token], the
 * server's `feed_token`, up to which every change is in what the device keeps of that
 * feed's listing. Some after it can be in it too, when the listing was read whole and a
 * later page showed them, and applying them again from the feed leaves them as they are.
 * [feed] names which, "one_time_expenses" for the expenses in
 * [ListedOneTimeExpenseEntity], and later one per group.
 *
 * Written in the transaction that applies the changes it is past, so the two never
 * disagree, which the server relies on: handing the token over tells it the device has
 * every change before it, and it forgets them. No row means the listing has to be read
 * whole, which is where every feed starts, and where one goes back to when the server can
 * no longer answer from the token or when what is kept no longer reads, see
 * `expense.OneTimeExpenseSync.readListing`.
 */
@Entity
class ChangeFeedEntity(
    @PrimaryKey(autoGenerate = false) val feed: String,
    val token: String,
)
