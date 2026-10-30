package database.data.local.entity

import androidx.room.Entity

/**
 * An expense of the group [groupUuid] the server lists at a version this device cannot read yet:
 * sealed under a generation of the group's key it neither holds nor can get back through the chain
 * of links the server keeps, see `group.GroupKeyChain`. A rotation's key the sync has yet to read,
 * say, or one a group brought back after a log-out never gets, see `group.GroupKeyCopies`.
 *
 * Kept, rather than dropped, as the history of a group's information keeps a version it cannot
 * read yet, see `group.GroupHistoryError.Unreadable`: the expense exists, and the group's balances
 * are short of it until it opens, which the group's screen says. Whatever version of it this device
 * accepted before stays shown in the meantime, see [ExpenseEntity]. Each pass of the sync over the
 * group replaces the group's rows with what it could not read, see `group.GroupExpenseSync`.
 */
@Entity(primaryKeys = ["groupUuid", "expenseUuid"])
class UnreadableGroupExpenseEntity(
    val groupUuid: String,
    val expenseUuid: String,
)
