package expense

import channel.DirectChannelError
import database.data.local.dao.OwedExpenseKeyDao
import database.data.remote.DirectMessageKindDto
import database.data.remote.MessageLimitScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageSender
import message.DirectSendError
import message.ResendScheduler
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Hands over the keys to the user's own one-time expenses that somebody named on them has not
 * been handed yet, see [database.data.local.entity.OwedExpenseKeyEntity], once the expense, as
 * this device shows it, names them. What `OneTimeExpenseSync.shareOwedKeys` runs.
 *
 * Each key goes only to somebody the creditor's own record names, see [keyFor], and not on the
 * strength of the expense's rows alone: those are refilled from the server.
 *
 * [record] is [OneTimeExpenseKeyRepository.record], and [send] is [DirectMessageSender.send],
 * or what a test stands in for either.
 */
class ExpenseKeyDelivery internal constructor(
    private val owedExpenseKeyDao: OwedExpenseKeyDao,
    private val record: suspend (expenseUuid: String) -> OneTimeExpenseKeyRecord?,
    private val send: suspend (recipientUserUuid: String, payload: ByteArray) -> EmptyResult<DirectSendError>,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        owedExpenseKeyDao: OwedExpenseKeyDao,
        keyRepository: OneTimeExpenseKeyRepository,
        directMessageSender: DirectMessageSender,
        resendScheduler: ResendScheduler,
    ) : this(
        owedExpenseKeyDao = owedExpenseKeyDao,
        record = { expenseUuid -> keyRepository.record(expenseUuid = expenseUuid) },
        send = { recipientUserUuid, payload ->
            directMessageSender.send(
                recipientUserUuid = recipientUserUuid,
                payload = payload,
                // So a locked phone can say an expense arrived rather than nothing.
                kind = DirectMessageKindDto.ONE_TIME_EXPENSE,
            )
        },
        resendScheduler = resendScheduler,
    )

    /**
     * Serializes [handOver], which an expense being written and a sync can both reach at once.
     * Each would read the same keys as still owed, and hand them over twice.
     */
    private val mutex = Mutex()

    /**
     * Hands every recipient the keys owed to them that [ownUserUuid], the creditor, holds the
     * records of.
     *
     * One person at a time, and as few messages per person as their keys fit in: one share, or
     * as many as it takes to keep each within what the server takes of one message, see
     * [ExpenseKeyShare.chunked]. The server counts messages against two allowances, and running
     * past either holds the rest back until it reopens, when the pass runs again, see
     * [ResendScheduler]. Past what the user may send one recipient, theirs wait and the pass goes
     * on to the others, though what the user may send everybody can still refuse them; past that,
     * the pass stops. A share refused, for a limit or for anything else, holds back the shares
     * after it, and whatever waits for a recipient is batched with what they are owed since at the
     * next pass, no more keys of it than a recipient reads of one share, see
     * [ExpenseKeyShare.MAX_KEYS]. Past that many, the rest wait for the pass after.
     *
     * Every recipient owed is attempted until the user's allowance of messages runs out, and
     * the first failure is reported. A refusal for either allowance is not one: the resend it
     * books is what comes of it. Nor is a `404`, see [DirectSendError.Network].
     */
    suspend fun handOver(ownUserUuid: String): EmptyResult<OneTimeExpenseSyncError> = mutex.withLock {
        val owed = owedExpenseKeyDao.getListed()

        if (owed.isEmpty()) return@withLock Result.Success(Unit)

        var failure: OneTimeExpenseSyncError? = null

        recipients@ for ((recipientUuid, rows) in owed.groupBy { it.recipientUuid }) {
            val keys = rows.mapNotNull { row ->
                record(row.expenseUuid)?.keyFor(requesterUuid = recipientUuid, ownUserUuid = ownUserUuid)
            }.take(ExpenseKeyShare.MAX_KEYS)

            if (keys.isEmpty()) continue

            for (share in ExpenseKeyShare(keys = keys).chunked()) {
                val error = when (val sent = send(recipientUuid, share.serialize())) {
                    is Result.Success -> {
                        owedExpenseKeyDao.delete(
                            recipientUuid = recipientUuid,
                            expenseUuids = share.keys.map { it.expenseUuid },
                        )

                        continue
                    }

                    is Result.Error -> sent.error
                }

                when (error) {
                    // Nobody to hand the keys to: they deleted their account, or the server offers
                    // no way to reach them that this build can use, and should they come back,
                    // they ask for the keys and the records answer them.
                    DirectSendError.RecipientDeleted,
                    DirectSendError.Channel(DirectChannelError.Unreachable),
                        -> owedExpenseKeyDao.delete(
                        recipientUuid = recipientUuid,
                        expenseUuids = keys.map { it.expenseUuid },
                    )

                    // A recipient the server knows nothing of, which says nothing of their
                    // account, see DirectSendError.Network: the keys wait for the next pass, as
                    // after any other failure, but it is not reported.
                    DirectSendError.Network(NetworkError.NOT_FOUND) -> Unit

                    // Somebody this device no longer has pinned, or whose identity is in
                    // doubt. No retry changes either, so the keys wait until that does.
                    DirectSendError.Channel(DirectChannelError.NotPinned),
                    DirectSendError.Channel(DirectChannelError.IdentityChanged),
                        -> Unit

                    is DirectSendError.RateLimited -> {
                        resendScheduler.resendAfter(seconds = error.retryAfterSeconds)

                        if (error.scope == MessageLimitScope.SENDER) break@recipients
                    }

                    else -> failure = failure ?: error.toSyncError()
                }

                // Whatever refused this share would refuse the next ones just the same.
                continue@recipients
            }
        }

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}
