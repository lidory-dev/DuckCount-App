package app.lidory.duckcount.push

import expense.GroupExpenseUpdate

/**
 * How many updates to the expenses of one of the user's groups one sync brings before they are told
 * in one notification rather than one each, see [ExpenseNotifier.notifyGroupExpenses].
 *
 * Four, where Android starts bundling an app's notifications of itself: from there on they are a
 * heap to open rather than rows to read. A phone that was offline while somebody entered a group's
 * weekend catches up on dozens in one sync, and a notification each, each of them ringing, is how a
 * channel gets muted.
 *
 * Counted in each group on its own, see [groupExpenseAnnouncementsOf]: three expenses of one group
 * and one of another are four rows to read, and are told as such.
 */
internal const val MANY_GROUP_EXPENSE_UPDATES = 4

/**
 * How the updates one sync brought to the expenses of one of the user's groups are told, see
 * [groupExpenseAnnouncementsOf].
 */
internal sealed interface GroupExpenseAnnouncement {
    /** The updates told, all of one group, in the order the sync brought them. */
    val updates: List<GroupExpenseUpdate>

    /** The UUID of the group they are all of. */
    val groupUuid: String get() = updates.first().groupUuid

    /** The title of the group they are all of, as this device shows it. */
    val groupTitle: String get() = updates.first().groupTitle

    /** One notification per expense, under a heading of the group's own when there are two or three. */
    data class OneEach(override val updates: List<GroupExpenseUpdate>) : GroupExpenseAnnouncement {
        /** Whether they go under a heading of the group's, there being more than one. */
        val hasHeading: Boolean get() = updates.size > 1
    }

    /** One notification for all of them, naming the group and saying how many, [count]. */
    data class InOne(override val updates: List<GroupExpenseUpdate>) : GroupExpenseAnnouncement {
        val count: Int get() = updates.size
    }
}

/**
 * How the [updates] of one sync are told, the updates of each group on their own: one notification
 * each, or one for them all from [MANY_GROUP_EXPENSE_UPDATES] on. In the order the sync first
 * brought an update of each group.
 *
 * Never one counting the expenses of several groups together: how many were updated across the
 * user's groups says neither where to look nor whose they are, and a sync catching up on two groups
 * is two things that happened, each told in a notification of its own.
 *
 * The groups are told apart by their UUID rather than their title, which their owner chose: two of
 * the user's groups may well be called the same, and a count naming that title would put the
 * expenses of both in one.
 */
internal fun groupExpenseAnnouncementsOf(updates: List<GroupExpenseUpdate>): List<GroupExpenseAnnouncement> =
    updates.groupBy { it.groupUuid }.values.map { ofGroup ->
        if (ofGroup.size < MANY_GROUP_EXPENSE_UPDATES) {
            GroupExpenseAnnouncement.OneEach(updates = ofGroup)
        } else {
            GroupExpenseAnnouncement.InOne(updates = ofGroup)
        }
    }

/**
 * What the notifications about an expense of one of the user's groups told one each are about, see
 * [ExpenseNotifier.notifyGroupExpenses], the group's UUID following it, see [groupExpenseTagOf].
 *
 * A tag per group, so that what [PostedNotifications] records of each group's is recorded apart: a
 * catch-up of one group takes down what was told of its own expenses, and its heading once none is
 * left, without reading what is recorded of any other group, see [groupExpenseTakedownOf].
 */
private const val GROUP_EXPENSE_TAG = "group-expense:"

/**
 * The tag the expenses of the group [groupUuid] told one each go up and are recorded under, and a
 * catch-up of that group reads and takes them down under, see [groupExpenseTakedownOf].
 */
internal fun groupExpenseTagOf(groupUuid: String): String = GROUP_EXPENSE_TAG + groupUuid

/**
 * What a sync telling the updates of a group in one notification takes down of what syncs before it
 * told one each of that group, see [groupExpenseTakedownOf].
 */
internal data class GroupExpenseTakedown(
    /**
     * The tag the group's expenses told one each went up and were recorded under, and are taken down
     * under, see [groupExpenseTagOf].
     */
    val tag: String,

    /** The ids of the notifications of the expenses it counts, recorded up or not. */
    val ids: Set<Int>,

    /** The ids recorded up of the group's expenses besides, which stay, under the group's heading. */
    val left: Set<Int>,
) {
    /** Whether the group's heading goes too, nothing recorded of the group being left under it. */
    val takesDownHeading: Boolean get() = left.isEmpty()
}

/**
 * What telling the updates of [announcement] in one notification takes down, [takeRecorded] handing
 * over the ids recorded up under a tag, forgetting them, as [PostedNotifications.takeAll] does: those
 * of the expenses of its group told one each so far, read under the tag of its group, see
 * [groupExpenseTagOf]. The caller records again those it leaves up, [GroupExpenseTakedown.left].
 *
 * The notification of each expense it counts, which says what may no longer be so: the expense was
 * edited since, or deleted. Told one each, the update would have gone up in its place; told in one,
 * it would leave the old one up beside the count that takes it in.
 *
 * And the group's heading once nothing recorded of the group is left under it, a heading with
 * nothing under it counting expenses that are told nowhere. Only then: taking a heading down takes
 * down whatever is under it. One the user dismissed is still recorded, the app having no way of
 * knowing, see [PostedNotifications], so the heading may yet be left up over nothing, but is never
 * taken down over something.
 *
 * Of its own group alone, each group's being recorded apart: what is recorded of another group's
 * expenses is not even read, being none of what it counts, and their heading not over its own.
 */
internal fun groupExpenseTakedownOf(
    announcement: GroupExpenseAnnouncement.InOne,
    takeRecorded: (tag: String) -> Set<Int>,
): GroupExpenseTakedown {
    val tag = groupExpenseTagOf(groupUuid = announcement.groupUuid)

    val ids = announcement.updates.mapTo(mutableSetOf()) { it.expenseUuid.hashCode() }

    return GroupExpenseTakedown(tag = tag, ids = ids, left = takeRecorded(tag) - ids)
}
