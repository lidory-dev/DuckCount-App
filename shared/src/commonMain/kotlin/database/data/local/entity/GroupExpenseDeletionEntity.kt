package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Whether an expense of a group is deleted, as the newest of the signed deletion flags of it this
 * device has shown says, and who set that flag, see `group.GroupExpenseSealing`. No flag is no
 * row: an expense nobody deleted is not.
 *
 * Apart from the expense's versions, as deleting and restoring are no edit: they use up none of
 * the edits an expense takes, and leave its versions as they are, so that one that had every edit
 * it takes is still deleted and restored. The flag numbers its changes apart, [deletionRevision],
 * and only one newer than the flag shown is stored over it, see
 * `database.data.local.dao.ExpenseDao.storeGroupExpenseDeletions`: one older is what a server
 * putting back the flag from before a deletion, to have the expense restored, looks like.
 *
 * [ExpenseEntity.isDeleted] says what the row does, set in the same transaction, and kept so by
 * every version stored after it, see `database.data.local.dao.ExpenseDao.storeGroupExpenses`, so
 * that what reads a group's expenses, its balances and its list, reads whether each is deleted off
 * the expense itself.
 */
@Entity
class GroupExpenseDeletionEntity(
    @PrimaryKey(autoGenerate = false) val expenseUuid: String,

    /** The revision of the flag shown, 1 for the expense's first deletion and one more for each change since. */
    val deletionRevision: Long,

    /** What the flag shown says. */
    val isDeleted: Boolean,

    /**
     * The member who set it, as they signed it: any member deletes and restores any expense of
     * their group but a settlement, which its recipient alone deletes, and nobody restores.
     */
    val authorUuid: String,
)
