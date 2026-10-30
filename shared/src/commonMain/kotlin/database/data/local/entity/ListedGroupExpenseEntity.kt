package database.data.local.entity

import androidx.room.Entity

/**
 * An expense of the group [groupUuid] as the server last listed it to the user: [json] is the
 * listing's entry for it exactly as written, its content still sealed, a
 * `group.ListedGroupExpenseDto` to this build.
 *
 * What [ListedOneTimeExpenseEntity] is to one-time expenses, one listing per group, each kept up to
 * date with its own feed of changes, see [ChangeFeedEntity]: read whole once, then brought up to
 * date with what changed, see `group.GroupExpenseSync`, which opens on every pass what it holds
 * that this device does not show yet, those whose key only arrived since included.
 *
 * Kept once the user is no longer in the group, removed or left, as the expenses it opened are: the
 * server lists the group's expenses to its current members alone, and nothing reads this again
 * then. Gone at log-out, with the rest.
 */
@Entity(primaryKeys = ["groupUuid", "expenseUuid"])
class ListedGroupExpenseEntity(
    val groupUuid: String,
    val expenseUuid: String,
    val json: String,
)
