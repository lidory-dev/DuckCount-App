package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Whether the creditor of a one-time expense marked it as paid back, as the newest of their
 * signed marks this device has shown says, see `expense.SettledMark`. No mark is no row: an
 * expense nobody marked is not paid back.
 *
 * Apart from the expense's row for the reason [OneTimeExpenseRevisionEntity] is: a sync deletes
 * the row whenever the server stops listing the expense, and a mark that went with it would let
 * the server leave the expense out once, then serve a mark from before the newest.
 */
@Entity
class OneTimeExpenseSettledMarkEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** When the creditor made the newest mark shown, in milliseconds since the epoch, as signed. */
    val markedAt: Long,

    /** What the newest mark shown says. */
    val isSettled: Boolean,
) {

    /**
     * Whether a mark made at [markedAt] is to be shown in place of this one, being newer.
     *
     * One made at the same time is not: switching between two marks at one time is the going
     * back the time is there to refuse. They only exist when the creditor signs twice at one
     * time, in the same millisecond of their clock, or again after an answer was lost while
     * their clock was behind the newest shown, which signs the same thing again. A mark whose
     * answer was lost is newer than what this device shows, and is shown once a sync brings it.
     */
    fun isReplacedBy(markedAt: Long): Boolean = markedAt > this.markedAt
}
