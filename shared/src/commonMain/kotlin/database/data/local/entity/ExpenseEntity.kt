package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

@Entity
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val title: String,
    val timestamp: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,

    /** Whether the expense is the payment settling up a group's debts, which no one-time expense is. */
    val isSettlement: Boolean = false,

    val currency: Currency,
    val groupUuid: String? = null,
    val isDeleted: Boolean = false,

    /**
     * The revision the version this row holds was signed at, see `expense.OneTimeExpenseSealing`
     * for a one-time expense and `group.GroupExpenseSealing` for a group's; null only for a row
     * written on this device alone, as the iOS form still writes one. Its history is told from it: which
     * versions came before it, and whether it is the expense as created.
     *
     * Not [ExpenseRevisionEntity.newestRevision], which an edit of a one-time expense reserves
     * before it is sent, and which stays ahead of the row when the edit gets no answer.
     */
    val revision: Long? = null,

    /**
     * Who wrote the version of a group expense this row holds, as it is signed, see
     * `group.GroupExpenseSealing`: any member may write any version but a settlement's, see
     * [isSettlement]. Null for a one-time expense, whose every version its creditor signs.
     */
    val authorUuid: String? = null,
)
