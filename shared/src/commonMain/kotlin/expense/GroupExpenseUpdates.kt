package expense

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.UserDao
import database.domain.ExpenseWithBeneficiaries
import group.GroupExpenseChange
import group.GroupExpenseSync

/** A version of an expense of one of the user's groups a sync stored, which concerns them and somebody else wrote. */
data class GroupExpenseUpdate(
    val groupUuid: String,

    /** The group's title, as this device shows it. */
    val groupTitle: String,

    val expenseUuid: String,

    /** Its title as this version has it, which a settlement has none of. */
    val title: String,

    /**
     * Whether it is the payment settling up the group's debts, see
     * [ExpenseWithBeneficiaries.isSettlement], which its recipient alone writes: told by its
     * [amount], having no title to be told by, to the member who paid it.
     */
    val isSettlement: Boolean,

    /** Its amount, in the group's currency, whose symbol is [currencySymbol]. */
    val amount: String,

    val currencySymbol: String,

    /** Null when this device has yet to read the name of whoever wrote it. */
    val authorFullName: String?,

    val kind: Kind,
) {
    /** What its author did to it, as far as the version stored and the one it replaced tell. */
    enum class Kind {
        /** Added it to the group, or made it readable to this device for the first time. */
        ADDED,

        EDITED,

        DELETED,

        RESTORED,
    }
}

/**
 * Tells which of the versions of the expenses of the user's groups a sync stored are worth telling
 * them about, see [announcedUpdates].
 *
 * The signal a member's write wakes the user with says that something changed in one of their
 * groups, and neither which group nor whether it concerns them: the server cannot tell, the payer
 * and the beneficiaries being sealed with the rest of the expense. So, as [ExpenseChanges] does for
 * the one-time expenses, this looks at what the sync left behind: each version it stored, with the
 * one it replaced, see [GroupExpenseSync].
 *
 * Unlike a one-time expense's creditor, whoever wrote it is named whether or not they are a
 * contact, by the name the group's screens show for them, see [UserDao.getDisplayName]: any member
 * may write any expense, and one the user paid for or shares in is their business, whoever wrote
 * it.
 */
class GroupExpenseUpdates(
    private val groupDao: GroupDao,
    private val userDao: UserDao,
    private val identityConflictDao: IdentityConflictDao,
) {
    /** What of [changes], a sync's, the user is told about, see [announcedUpdates]. */
    suspend fun of(changes: List<GroupExpenseChange>): List<GroupExpenseUpdate> {
        if (changes.isEmpty()) return emptyList()

        val selfUuid = getAuthenticatedUserUuid() ?: return emptyList()

        val announced = announcedUpdates(
            changes = changes,
            selfUuid = selfUuid,
            blocked = identityConflictDao.getBlocked().toHashSet(),
        )

        val groupTitles = mutableMapOf<String, String?>()
        val names = mutableMapOf<String, String?>()

        return announced.mapNotNull { (change, kind) ->
            // None once a session that ended as the sync ran emptied the database, the groups with it.
            val groupTitle = groupTitles.getOrPut(change.groupUuid) {
                groupDao.get(uuid = change.groupUuid)?.title
            } ?: return@mapNotNull null

            val expense = change.expense

            GroupExpenseUpdate(
                groupUuid = change.groupUuid,
                groupTitle = groupTitle,
                expenseUuid = expense.uuid,
                title = expense.title,
                isSettlement = expense.isSettlement,
                amount = expense.amount,
                currencySymbol = expense.currency.symbol,
                authorFullName = names.getOrPut(change.authorUuid) {
                    userDao.getDisplayName(uuid = change.authorUuid)
                },
                kind = kind,
            )
        }
    }
}

/**
 * Which of the [changes] a sync stored are told about, and as what: those that concern the user
 * [selfUuid], who paid for the version stored or shares in it, or did in the one it replaced, which
 * somebody else wrote, nobody [blocked].
 *
 * None that the user wrote, on another of their devices or before a log-out. None of a group whose
 * expenses were listed whole for the first time on this device, see
 * [GroupExpenseChange.isFirstListing]: every one of them is new to it after a join or a log-out, and
 * none of them news to the user. And none from somebody blocked, whose signals wake the user no
 * more, see [push.WakeList]: a sync somebody else woke the user for would otherwise tell of them all
 * the same.
 *
 * An expense new to this device is added, unless it is deleted already, which counts in no balance.
 * A version deleting the one shown is a deletion, one undoing that a restoration, and any other an
 * edit, but one of an expense that stays deleted, which changes nothing the user's balances show.
 */
internal fun announcedUpdates(
    changes: List<GroupExpenseChange>,
    selfUuid: String,
    blocked: Set<String>,
): List<Pair<GroupExpenseChange, GroupExpenseUpdate.Kind>> =
    changes
        .filter { !it.isFirstListing && it.authorUuid != selfUuid && it.authorUuid !in blocked }
        .filter { it.expense.concerns(userUuid = selfUuid) || it.previous?.concerns(userUuid = selfUuid) == true }
        .mapNotNull { change ->
            val previous = change.previous
            val isDeleted = change.expense.isDeleted

            val kind = when {
                previous == null -> if (isDeleted) return@mapNotNull null else GroupExpenseUpdate.Kind.ADDED
                !previous.isDeleted && isDeleted -> GroupExpenseUpdate.Kind.DELETED
                previous.isDeleted && !isDeleted -> GroupExpenseUpdate.Kind.RESTORED
                isDeleted -> return@mapNotNull null
                else -> GroupExpenseUpdate.Kind.EDITED
            }

            change to kind
        }

/** Whether [userUuid] paid for this expense, or shares in it. */
private fun ExpenseWithBeneficiaries.concerns(userUuid: String): Boolean =
    creditorUuid == userUuid || beneficiaries.any { it.userUuid == userUuid }
