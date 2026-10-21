package expense

import channel.DirectChannelError
import channel.DirectChannelRepository
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.PendingExpenseKeyState
import database.data.remote.MessageLimitScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageSender
import message.DirectSendError
import message.ResendScheduler
import message.givesBudgetBack
import util.EmptyResult
import util.Result
import util.backoffFor
import util.nowEpochMilliseconds
import util.spendMessageBudget

/**
 * Asks each creditor for the keys to their one-time expenses this device is still missing. What
 * `OneTimeExpenseSync.requestMissingKeys` runs.
 *
 * [expectedFrom] is [OneTimeExpenseKeyRepository.expectedFrom], and [send] is
 * [DirectMessageSender.send], or what a test stands in for either.
 */
class ExpenseKeyPull internal constructor(
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val expectedFrom: suspend (creditorUuid: String) -> ExpectedKeys,
    private val send: suspend (recipientUserUuid: String, payload: ByteArray) -> EmptyResult<DirectSendError>,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        pendingExpenseKeyDao: PendingExpenseKeyDao,
        keyRepository: OneTimeExpenseKeyRepository,
        directMessageSender: DirectMessageSender,
        resendScheduler: ResendScheduler,
    ) : this(
        pendingExpenseKeyDao = pendingExpenseKeyDao,
        expectedFrom = { creditorUuid -> keyRepository.expectedFrom(creditorUuid = creditorUuid) },
        send = { recipientUserUuid, payload ->
            directMessageSender.send(recipientUserUuid = recipientUserUuid, payload = payload)
        },
        resendScheduler = resendScheduler,
    )

    /**
     * Serializes [requestMissingKeys], which a push and the list being opened can both reach
     * at once. Each would read the same keys as due, ask for them twice, and spend the
     * creditor's budget of requests twice over, see [claimRequest].
     */
    private val mutex = Mutex()

    /**
     * Asks each creditor for the keys this device is still missing from them, of the ones it
     * takes from them, see [ExpectedKeys]. [ownUserUuid] is the user's, who is never asked.
     *
     * One message per creditor rather than per expense: somebody coming back from a log-out
     * is missing everything a creditor ever sent them, and the allowance the server enforces
     * is counted in messages.
     *
     * Asked for at once, with no settling period first. There used to be one, against an
     * expense reaching the server before the message carrying its key did. That race is
     * gone: the push a beneficiary is woken by *is* the key message, so by the time they
     * look, the key is already there to be read. What the period did instead was hold up
     * the one case this exists for, somebody logging back in to find expenses whose keys
     * were sent while they were away and can never be read.
     *
     * How often a creditor is asked is otherwise theirs to decide, and every request is one
     * of the user's own allowance of messages, the one their key shares need. Each expense of
     * theirs the user cannot open is a row due at once; making one, deleting it and making
     * another costs them nothing and hands the user a fresh row; more of them than a request
     * names stay due for the next sync; and they can wake this device for one whenever they
     * like. So each creditor is asked out of a budget, two requests at once and one more an
     * hour after that, see [claimRequest]: the shape of the one an honest creditor answers
     * from, see [DirectChannelRepository.claimReply], which would drop most of what it holds
     * back anyway. Not all of it: two requests a creditor reads in one pass are answered as
     * one, see [KeyRequestsInPass], and the one their second answer would have gone to waits
     * for the hour. A request names at most a hundred of the keys still due, so somebody back
     * from a log-out with more than that missing from one creditor asks that creditor at the
     * first two syncs, and then at most once an hour, at the first sync the budget allows.
     *
     * A refusal for the user's allowances of messages is no try: the creditor's rows stay due,
     * their budget goes back, and they are asked as the pass runs again once the allowance
     * reopens, see [ResendScheduler]. Past what the user may send that creditor, the next one
     * is asked; past what they may send everybody, the pass stops.
     */
    suspend fun requestMissingKeys(ownUserUuid: String): EmptyResult<OneTimeExpenseSyncError> = mutex.withLock {
        val pending = pendingExpenseKeyDao.getInState(state = PendingExpenseKeyState.PENDING)

        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        val now = nowEpochMilliseconds()

        val due = pending.filter { row ->
            // The user's own expense: there is nobody to ask, and the server refuses a
            // message addressed to oneself, so it would fail forever.
            row.creditorUuid != ownUserUuid &&
                    (row.lastRequestedAt == null || now - row.lastRequestedAt >= backoffFor(row.requestCount))
        }

        var failure: OneTimeExpenseSyncError? = null
        var sent = 0

        for ((creditorUuid, waiting) in due.groupBy { it.creditorUuid }) {
            if (sent >= MAX_CREDITORS_PER_SYNC) break

            // Only for the keys this device takes from them, see [ExpectedKeys]: the creditor
            // is the server's word, and anybody can name the user on an expense. Being pinned
            // is not enough, nor having a channel. Sending a contact request pins whoever the
            // server said the username was, and the pin stays whether they answer or not, and
            // whether the server stored the request or not; a contact removed keeps their pin
            // and their channel. So a contact is asked for anything, and anybody else only for
            // what was awaited from them when they stopped being one. Their other rows wait,
            // unasked, until they are a contact again.
            val rows = expectedFrom(creditorUuid).among(rows = waiting)

            if (rows.isEmpty()) continue

            // Read before it is spent, to be put back should the request never leave this
            // device, or be refused for a limit.
            val budgetFullAt = pendingExpenseKeyDao.getRequestBudgetFullAt(creditorUuid = creditorUuid)

            // A creditor whose budget is spent is left the way one with nothing to be asked for
            // is: their rows stay due with nothing tried, and are asked for at the first sync it
            // allows.
            if (!claimRequest(creditorUuid = creditorUuid, budgetFullAt = budgetFullAt, now = now)) continue

            val expenseUuids = rows.map { it.expenseUuid }.take(ExpenseKeyRequest.MAX_EXPENSES)

            val request = send(creditorUuid, ExpenseKeyRequest(expenseUuids = expenseUuids).serialize())

            when (request) {
                is Result.Success -> {
                    pendingExpenseKeyDao.markRequested(expenseUuids = expenseUuids, requestedAt = now)

                    sent++
                }

                is Result.Error -> {
                    // Given back when it certainly never left this device: kept, it would spend
                    // the budget of a creditor this device cannot reach yet, unpinned or in
                    // doubt, at every sync with nothing sent. And when it was refused for a
                    // limit, so as not to hold back the resend that refusal books.
                    if (request.error.givesBudgetBack()) {
                        pendingExpenseKeyDao.setRequestBudget(
                            budget = KeyRequestBudgetEntity(
                                creditorUuid = creditorUuid,
                                // Never spent before, it goes back as a moment already past.
                                budgetFullAt = budgetFullAt ?: now,
                            ),
                        )
                    }

                    when (val error = request.error) {
                        // The server knows of no such user, which for an account is final: an
                        // expense outlives its creditor, and the key does not. True of every
                        // expense of theirs, not only the ones this request named.
                        DirectSendError.RecipientGone -> pendingExpenseKeyDao.setState(
                            expenseUuids = rows.map { it.expenseUuid },
                            state = PendingExpenseKeyState.CREDITOR_GONE,
                        )

                        // No channel could be built to them, which is never final: they have
                        // published no key package yet, the one served has outlived its lifetime
                        // because they have not opened the app in months, or their account is
                        // gone, which the lookup answers the same way. So their rows
                        // wait on the backoff, all of them, or the next sync would fetch another
                        // key package for the rest. Not reported: a retry would find nothing due.
                        DirectSendError.Channel(DirectChannelError.Unreachable),
                            -> pendingExpenseKeyDao.markRequested(
                            expenseUuids = rows.map { it.expenseUuid },
                            requestedAt = now,
                        )

                        // Somebody this device knows but holds no pin for: a contact the vault gave
                        // back without one, see [vault.VaultContact]. Building would pin whatever the
                        // server served for them, so the rows wait until they are pinned.
                        DirectSendError.Channel(DirectChannelError.NotPinned) -> Unit

                        // Somebody whose identity is in doubt, who is not asked for anything until
                        // that is settled, which no retry does.
                        DirectSendError.Channel(DirectChannelError.IdentityChanged) -> Unit

                        // Past what the user may send this creditor, their rows stay due and
                        // the next creditor is asked; past what the user may send everybody,
                        // sending more only pushes the count further past it. Either way they
                        // are asked again once the limit reopens, their budget given back above.
                        is DirectSendError.RateLimited -> {
                            resendScheduler.resendAfter(seconds = error.retryAfterSeconds)

                            if (error.scope == MessageLimitScope.SENDER) break
                        }

                        else -> failure = failure ?: error.toSyncError()
                    }
                }
            }
        }

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Whether this device may ask [creditorUuid], whose budget is whole again at
     * [budgetFullAt], for keys now, which it then records it does, see [requestMissingKeys]
     * and [KeyRequestBudgetEntity].
     *
     * Claimed before the request is sent, and kept when the send fails unless it certainly
     * never left this device or was refused for a limit, see [givesBudgetBack]: the server
     * counts one to an account that is gone, and one it stored although its response never
     * made it back. Only ever under [mutex], since the budget is read before it is written.
     */
    private suspend fun claimRequest(creditorUuid: String, budgetFullAt: Long?, now: Long): Boolean {
        val fullAt = spendMessageBudget(
            budgetFullAt = budgetFullAt,
            now = now,
            onlyWhenWhole = false,
        ) ?: return false

        pendingExpenseKeyDao.setRequestBudget(
            budget = KeyRequestBudgetEntity(creditorUuid = creditorUuid, budgetFullAt = fullAt),
        )

        return true
    }
}

/** How many creditors are asked in one sync, so a re-login does not ask everyone at once. */
private const val MAX_CREDITORS_PER_SYNC = 3
