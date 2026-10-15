package expense

import database.data.local.dao.ContactDao
import database.data.local.dao.ExpenseCreditor
import database.data.local.dao.ExpenseDao
import database.data.local.dao.UserDao

/** A one-time expense this device could not read before a sync ran, and now can. */
data class ExpenseArrival(
    val expenseUuid: String,
    val creditorUuid: String,

    /** Null when this device has yet to read the name of the creditor, a contact. */
    val creditorFullName: String?,
)

/** Which one-time expenses this device could read at one moment, to compare a later one against. */
class ExpenseSnapshot internal constructor(
    internal val readable: Set<String>,
)

/**
 * Tells which expenses became readable while a sync was running.
 *
 * A push signal says that *something* arrived and never what, so the only way to say
 * anything true to the user is to look at what the sync left behind.
 *
 * Readable is the thing worth announcing, not merely present. An expense turns up on the
 * server before the message carrying its key does, and announcing it in between would mean
 * telling somebody about an expense and being unable to say who it is from or what it cost.
 * Diffing over what can actually be read gets the timing right for free: the expense is
 * announced when its key lands, whether that is a second later or after a log-out and a
 * request for it, and only once either way.
 *
 * Only a contact's expenses are announced, see [announcedArrivals].
 */
class ExpenseArrivals(
    private val expenseDao: ExpenseDao,
    private val userDao: UserDao,
    private val contactDao: ContactDao,
) {
    /** Which one-time expenses this device can read right now. */
    suspend fun snapshot(): ExpenseSnapshot =
        ExpenseSnapshot(readable = expenseDao.getOneTimeExpenseUuids().toHashSet())

    /** Which ones [snapshot] could not read and this device now can. */
    suspend fun since(snapshot: ExpenseSnapshot): List<ExpenseArrival> {
        val arrived = expenseDao.getOneTimeExpenseUuids().filterNot { it in snapshot.readable }

        if (arrived.isEmpty()) return emptyList()

        val announced = announcedArrivals(
            arrived = expenseDao.getOneTimeCreditors(uuids = arrived),
            contacts = contactDao.getUnblockedUserUuids().toHashSet(),
        )

        val names = mutableMapOf<String, String?>()

        return announced.map { expense ->
            ExpenseArrival(
                expenseUuid = expense.uuid,
                creditorUuid = expense.creditorUuid,
                creditorFullName = names.getOrPut(expense.creditorUuid) {
                    userDao.getFullName(uuid = expense.creditorUuid)
                },
            )
        }
    }
}

/**
 * Which of the expenses that [arrived] are announced: the ones the user's [contacts], blocked
 * ones aside, paid for.
 *
 * A former contact's expenses come in all the same, the keys to what they owed at the removal
 * among them, and show in the app without a word, as their signals no longer wake the user,
 * see [push.WakeList]: a sync somebody else woke the user for would otherwise tell of them all
 * the same. Nor are the user's own, which a sync makes readable again after a log-out: none of
 * them is news to the user.
 */
internal fun announcedArrivals(
    arrived: List<ExpenseCreditor>,
    contacts: Set<String>,
): List<ExpenseCreditor> = arrived.filter { it.creditorUuid in contacts }
