package database.data.local.entity

import androidx.room.Entity

/**
 * The key to one of the user's own one-time expenses, which [recipientUuid], somebody the
 * user named on it, has not been handed yet.
 *
 * Written before the expense goes to the server, not after: the server can store it and the
 * answer never arrive, and the key message is what wakes a beneficiary up, since the server
 * sends no push of its own for an expense. A share kept only in memory until the answer came
 * back was lost with it, and so was the one a pass gave up on at the message allowance.
 *
 * The key goes out once the expense, as this device shows it, names [recipientUuid], which
 * is when the write that named them has landed. Until then the creditor may still change
 * their mind, and the expense is no use to somebody the server does not list on it. The row
 * goes when the key does.
 *
 * The mirror of [PendingExpenseKeyEntity], which is the keys this device is owed.
 */
@Entity(primaryKeys = ["expenseUuid", "recipientUuid"])
class OwedExpenseKeyEntity(
    val expenseUuid: String,
    val recipientUuid: String,
)
