package database.data.local.entity

import androidx.room.Entity

/**
 * An expense of the group [groupUuid] the server lists at a version, or with a deletion flag, this
 * device cannot read yet: sealed under a generation of the group's key it neither holds nor can get
 * back through the chain of links the server keeps, see `group.GroupKeyChain`. The key of an epoch
 * the sync has yet to read the Commit leading to, say, or one a group brought back after a log-out
 * never gets, see `group.GroupKeyCopies`, or one of an epoch the creator's replacement of a Commit
 * undid, which no member holds, see `group.ReplaceGroupCommitBody`.
 *
 * Kept, rather than dropped, as the history of a group's information keeps a version it cannot
 * read yet, see `group.GroupHistoryError.Unreadable`: the expense exists, and the group's balances
 * are short of it until it opens, which the group's screen says. Whatever version of it this device
 * accepted before stays shown in the meantime, see [ExpenseEntity], deleted or not as the flag it
 * accepted before says, and the screen counts no expense it shows. One it shows no version of yet
 * stays unshown until the flag listed with it opens as well as its version, the two being stored
 * together, see `group.GroupExpenseSync`. Each pass of the sync over the group replaces the group's
 * rows with what it could not read.
 */
@Entity(primaryKeys = ["groupUuid", "expenseUuid"])
class UnreadableGroupExpenseEntity(
    val groupUuid: String,
    val expenseUuid: String,
)
