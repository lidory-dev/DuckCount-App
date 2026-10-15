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
     * The revision the version of a one-time expense this row holds was signed at, see
     * `expense.OneTimeExpenseSealing`; null for a group expense. Its history is told from it:
     * which versions came before it, and whether it is the expense as created.
     *
     * Not [OneTimeExpenseRevisionEntity.newestRevision], which an edit reserves before it is
     * sent, and which stays ahead of the row when the edit gets no answer.
     */
    val revision: Long? = null,
)
