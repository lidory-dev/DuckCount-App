package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The key that opens one one-time expense, as this device holds it.
 *
 * The server keeps the user's own copy with each expense, so that logging out does not
 * lose them, see `expense.OneTimeExpenseKeyRepository`. This is the device's copy, plus the
 * keys that are not verified yet.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class OneTimeExpenseKeyEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** The serialized record, see `OneTimeExpenseKeyRecord`. */
    val record: ByteArray,

    /**
     * Whether this key has been shown to open the expense it names.
     *
     * A key arrives in a message from somebody who could be lying, and a device that took
     * any thirty-two bytes at their word would stop asking for the real key and never open
     * the expense again. So a key is only verified once the expense has opened under it,
     * signed by the creditor it came from, and until then it is kept but not acted on. It
     * can also arrive before the expense it is for does, which is the ordinary case for a
     * key sent the moment an expense was created.
     */
    val isVerified: Boolean,
)
