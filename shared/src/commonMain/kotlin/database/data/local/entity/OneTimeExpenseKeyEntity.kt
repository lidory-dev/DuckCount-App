package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The key that opens one one-time expense, as this device holds it.
 *
 * The vault is where these live, so that logging out does not lose them, but reading the
 * vault is a network call and opening an expense list must not be one per expense. This is
 * the device's copy of what the vault holds, plus the ones on their way there.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class OneTimeExpenseKeyEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** The sealed record, as it is written to the vault. See `OneTimeExpenseKeyRecord`. */
    val record: ByteArray,

    /**
     * Whether this key has been shown to open the expense it names.
     *
     * A key arrives in a message from somebody who could be lying, and a device that took
     * any thirty-two bytes at their word would stop asking for the real key and never open
     * the expense again. So a key is only believed once it has opened the expense's title,
     * and until then it is kept but not acted on. It can also arrive before the expense it
     * is for does, which is the ordinary case for a key sent the moment an expense was
     * created.
     */
    val isVerified: Boolean,

    /**
     * Whether the vault holds it. Unverified keys are never written there: the vault is the
     * copy that outlives this device, and it is not the place for somebody else's guess.
     */
    val isBackedUp: Boolean,
)
