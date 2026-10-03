package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A key to one one-time expense that its creditor handed over, see
 * `expense.OneTimeExpenseMessageHandler`, and that has not been shown to open it yet.
 *
 * The nonce that came with it shows whose expense it is a key to, not that there is one:
 * anybody can draw a nonce, derive the UUID of an expense of theirs that was never created,
 * and send the user as many keys to those as they like. A key that works is kept for good,
 * see [OneTimeExpenseKeyEntity], so this one waits here until the expense, as the server
 * lists it, has opened under it, signed by the creditor it came from, and goes with the
 * first listing asked for after it arrived that shows the user on no such expense, see
 * `expense.OneTimeExpenseSync.refresh`. It can also arrive before a listing has shown this
 * device the expense, which is the ordinary case: the creditor hands it over the moment the
 * expense is stored.
 *
 * One per expense, since only a key from its creditor is kept, and a later one from them
 * replaces the earlier.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class UnverifiedExpenseKeyEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** The serialized record, see `OneTimeExpenseKeyRecord`. */
    val record: ByteArray,
)
