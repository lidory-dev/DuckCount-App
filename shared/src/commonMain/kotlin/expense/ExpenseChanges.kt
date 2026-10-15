package expense

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.ShownOneTimeExpense
import database.data.local.dao.UserDao

/** A one-time expense this device could read before a sync ran, which its creditor changed since. */
data class ExpenseChange(
    val expenseUuid: String,

    /** Null when this device holds no name for the creditor, who need not be a contact. */
    val creditorFullName: String?,

    /** Its title as changed, or as this device last showed it for one that is gone. */
    val title: String,

    val kind: Kind,
) {
    /**
     * What the creditor did to it, as far as what the sync left behind can tell.
     *
     * An edit and a mark are not either-or: one sync can bring a newer version and a mark
     * saying the opposite of the last one, and that is still one thing that happened to one
     * expense.
     */
    enum class Kind {
        /**
         * The creditor deleted it, or took the user off it. The server stops listing it to the
         * user either way, and this device cannot tell which.
         */
        REMOVED,

        EDITED,

        MARKED_SETTLED,

        MARKED_NOT_SETTLED,

        EDITED_AND_MARKED_SETTLED,

        EDITED_AND_MARKED_NOT_SETTLED,
    }
}

/** Which one-time expenses this device showed at one moment, and how, to compare a later one against. */
class ShownExpenseSnapshot internal constructor(
    internal val shown: Map<String, ShownOneTimeExpense>,
)

/**
 * Tells which of the expenses this device could already read changed while a sync was running.
 *
 * The signal a creditor's change wakes a beneficiary with says that something changed, and
 * neither which expense nor how: the server cannot even tell which way a mark went. So, as
 * [ExpenseArrivals] does for the expenses that became readable, this looks at what the sync
 * left behind, and compares it with what the device showed before.
 *
 * An expense this device could not read before the sync is no change to anything it showed,
 * and is [ExpenseArrivals]' to tell about.
 */
class ExpenseChanges(
    private val expenseDao: ExpenseDao,
    private val userDao: UserDao,
) {
    /** The one-time expenses this device shows right now. */
    suspend fun snapshot(): ShownExpenseSnapshot =
        ShownExpenseSnapshot(shown = expenseDao.getShownOneTimeExpenses().associateBy { it.uuid })

    /** What happened to the ones [snapshot] showed, see [changesBetween]. */
    suspend fun since(snapshot: ShownExpenseSnapshot): List<ExpenseChange> {
        val now = snapshot()

        // Read after the rows. A session can end while the sync runs, the server refusing it
        // included, and ending one forgets the user before it empties the database: rows read
        // emptied find nobody signed in, rather than every expense gone.
        val ownUserUuid = getAuthenticatedUserUuid() ?: return emptyList()

        val names = mutableMapOf<String, String?>()

        return changesBetween(before = snapshot, after = now, ownUserUuid = ownUserUuid)
            .map { (expense, kind) ->
                ExpenseChange(
                    expenseUuid = expense.uuid,
                    creditorFullName = names.getOrPut(expense.creditorUuid) {
                        userDao.getFullName(uuid = expense.creditorUuid)
                    },
                    title = expense.title,
                    kind = kind,
                )
            }
    }
}

/**
 * What happened between [before] and [after] to each expense [before] showed, with the
 * expense as [after] shows it, or as [before] did for one that is gone.
 *
 * Except [ownUserUuid]'s own expenses: a change to one of them that a sync brings in was made
 * by the user, on another of their devices.
 *
 * A newer revision is an edit. A mark is a change only when it says the opposite of the one
 * shown before: the creditor signing again what they had already said tells the user nothing.
 */
internal fun changesBetween(
    before: ShownExpenseSnapshot,
    after: ShownExpenseSnapshot,
    ownUserUuid: String,
): List<Pair<ShownOneTimeExpense, ExpenseChange.Kind>> =
    before.shown.values
        .filter { it.creditorUuid != ownUserUuid }
        .mapNotNull { was ->
            val now = after.shown[was.uuid] ?: return@mapNotNull was to ExpenseChange.Kind.REMOVED

            val isEdited = now.revision > was.revision
            val isMarked = now.isSettled != was.isSettled

            val kind = when {
                isEdited && isMarked -> if (now.isSettled) {
                    ExpenseChange.Kind.EDITED_AND_MARKED_SETTLED
                } else {
                    ExpenseChange.Kind.EDITED_AND_MARKED_NOT_SETTLED
                }

                isEdited -> ExpenseChange.Kind.EDITED

                isMarked -> if (now.isSettled) {
                    ExpenseChange.Kind.MARKED_SETTLED
                } else {
                    ExpenseChange.Kind.MARKED_NOT_SETTLED
                }

                else -> return@mapNotNull null
            }

            now to kind
        }
