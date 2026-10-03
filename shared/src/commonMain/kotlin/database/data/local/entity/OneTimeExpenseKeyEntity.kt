package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The key that opens one one-time expense, as this device holds it: the user's own, one that
 * has opened the expense, or one brought back from the user's copy on the server.
 *
 * The server keeps the user's own copy with each expense, so that logging out does not
 * lose them, see `expense.OneTimeExpenseKeyRepository`. This is the device's copy. A key that
 * arrived in a message and has not been shown to work yet is kept apart, see
 * [UnverifiedExpenseKeyEntity].
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class OneTimeExpenseKeyEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** The serialized record, see `OneTimeExpenseKeyRecord`. */
    val record: ByteArray,
)
