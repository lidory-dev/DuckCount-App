package expense

import authenticationUtils.getAuthenticatedUserUuid
import channel.DirectChannelError
import database.data.local.dao.ExpenseDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.PendingExpenseKeyState
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseDto
import message.DirectMessageSender
import message.DirectMessageSync
import message.DirectSendError
import util.EmptyResult
import util.Result
import util.nowEpochMilliseconds
import kotlin.uuid.Uuid

/**
 * Brings the user's one-time expenses up to date, and chases the keys to the ones this
 * device cannot open.
 *
 * The server tells every participant that an expense exists and who else is on it; only its
 * key says what it is for or what it costs. So a sync is two halves: read what the server
 * has and open what can be opened, then ask about the rest.
 *
 * Asking is driven by what is missing rather than by what arrived, which is what makes it
 * cover more than the case it was written for. A key message that was never sent, one that
 * was lost, and one that reached a device that had logged out and could never be read all
 * look the same from here: an expense the user is on, with no key for it.
 */
class OneTimeExpenseSync(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val keyRepository: OneTimeExpenseKeyRepository,
    private val directMessageSender: DirectMessageSender,
    private val directMessageSync: DirectMessageSync,
) {

    /**
     * Every step is attempted even when one fails, and the first failure is reported: they
     * are independent, and whatever did not get done is picked up by the next sync.
     */
    suspend fun sync(): EmptyResult<OneTimeExpenseSyncError> {
        var failure: OneTimeExpenseSyncError? = null

        when (val refreshed = refresh()) {
            is Result.Success -> Unit
            is Result.Error -> failure = refreshed.error
        }

        when (val requested = requestMissingKeys()) {
            is Result.Success -> Unit
            is Result.Error -> failure = failure ?: requested.error
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Reads what the server holds, opens what this device has the key for, and records the
     * rest as waiting.
     *
     * The keys come and go with the list: the ones this device lost are brought back from
     * the user's copies it carries, and a copy of every key the server is missing is sent
     * back, see [OneTimeExpenseKeyRepository]. Neither is reported when it fails: both are
     * decided afresh from the next listing, and the expenses on screen are fine either way.
     *
     * A version older than one this device has shown is not stored over it, see
     * `ExpenseDao.replaceAllOneTimeExpenses`.
     *
     * Sends no message: what is missing is written down here and asked for in
     * [requestMissingKeys], so that the request loop is not run from inside the
     * transaction that stores what it is about.
     */
    suspend fun refresh(): EmptyResult<OneTimeExpenseSyncError> {
        // The keys to these expenses arrive as direct messages, so read those first: doing
        // it the other way round means opening the list, finding an expense unreadable, and
        // asking its creditor for a key that was already sitting on the server.
        //
        // Not left to the push that carried it either. A push can be missed — the device
        // was off, the distributor dropped the subscription, a log-out took it with it —
        // and a key that was only ever going to arrive that way would never arrive at all,
        // leaving an expense invisible with nothing to bring it back. Opening the list is
        // the other moment the user is plainly asking to be up to date.
        //
        // Its failure is not reported: whatever was not read stays on the server, and the
        // expenses below are worth reading either way.
        directMessageSync.sync()

        val expenses = when (val listed = expenseApi.getOneTimeExpenses()) {
            // Only entries whose UUIDs are written as the server writes them, whether or not
            // they open. These UUIDs end up in rows, in key requests and in the library, which
            // throws on a string that is not valid UTF-16 rather than refusing it. An expense's
            // UUID also keys its row in the list.
            is Result.Success -> listed.data.filter { it.hasCanonicalUuids() }
            is Result.Error -> return Result.Error(OneTimeExpenseSyncError.Network(listed.error))
        }

        // Before deciding what is missing: an expense whose key was merely not brought back
        // yet looks exactly like one whose key never arrived, and would be asked for.
        keyRepository.restore(expenses = expenses)

        val held = keyRepository.held()

        val opened = mutableListOf<ExpenseDto>()
        val unopened = mutableListOf<ExpenseDto>()
        val decrypted = expenses.mapNotNull { expense ->
            val version = held[expense.uuid]?.let { expense.unsealOneTime(record = it.record) }

            if (version == null) {
                unopened += expense

                return@mapNotNull null
            }

            opened += expense

            version.expense.toExpenseWithBeneficiariesEntity() to version.revision
        }

        expenseDao.replaceAllOneTimeExpenses(
            expenses = decrypted,
            serverUuids = expenses.map { it.uuid },
        )

        reconcilePending(unopened = unopened)

        // A key that arrived before its expense did, or before this device could check it,
        // is verified only once the expense it names has opened under it, signed by the
        // creditor it came from.
        promoteVerifiedKeys(opened = opened, held = held)

        // Done after the rows are stored, because it is one request per key, and after the
        // promotion, so that a key verified just now goes up with this listing.
        keyRepository.backUp(expenses = expenses)

        return Result.Success(Unit)
    }

    /**
     * Asks each creditor for the keys this device is still missing from them.
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
     */
    suspend fun requestMissingKeys(): EmptyResult<OneTimeExpenseSyncError> {
        val ownUserUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseSyncError.NoSession)

        val pending = pendingExpenseKeyDao.getInState(state = PendingExpenseKeyState.PENDING)

        if (pending.isEmpty()) return Result.Success(Unit)

        val now = nowEpochMilliseconds()

        val due = pending.filter { row ->
            // The user's own expense: there is nobody to ask, and the server refuses a
            // message addressed to oneself, so it would fail forever.
            row.creditorUuid != ownUserUuid &&
                    (row.lastRequestedAt == null || now - row.lastRequestedAt >= backoffFor(row.requestCount))
        }

        var failure: OneTimeExpenseSyncError? = null
        var sent = 0

        for ((creditorUuid, rows) in due.groupBy { it.creditorUuid }) {
            if (sent >= MAX_CREDITORS_PER_SYNC) break

            val expenseUuids = rows.map { it.expenseUuid }.take(ExpenseKeyRequest.MAX_EXPENSES)

            val request = directMessageSender.send(
                recipientUserUuid = creditorUuid,
                payload = ExpenseKeyRequest(expenseUuids = expenseUuids).serialize(),
            )

            when (request) {
                is Result.Success -> {
                    pendingExpenseKeyDao.markRequested(expenseUuids = expenseUuids, requestedAt = now)

                    sent++
                }

                is Result.Error -> when (request.error) {
                    // The server knows of no such user, which for an account is final: an
                    // expense outlives its creditor, and the key does not.
                    DirectSendError.RecipientGone,
                    DirectSendError.Channel(DirectChannelError.Unreachable),
                        -> pendingExpenseKeyDao.setState(
                        expenseUuids = expenseUuids,
                        state = PendingExpenseKeyState.CREDITOR_GONE,
                    )

                    // Sending more only pushes the count further past the limit. The rest
                    // wait for the next sync.
                    DirectSendError.RateLimited -> return Result.Error(
                        OneTimeExpenseSyncError.Network(util.NetworkError.TOO_MANY_REQUESTS),
                    )

                    else -> failure = failure ?: request.error.toSyncError()
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Makes the waiting list say exactly which expenses cannot be opened right now.
     *
     * An expense that opened, or that the server no longer lists, is dropped from it. One
     * that is new to it is added with a clean slate; one already on it keeps what this
     * device has tried, because overwriting that would put the backoff back to zero on every
     * sync and turn a patient retry into a storm.
     */
    private suspend fun reconcilePending(unopened: List<ExpenseDto>) {
        if (unopened.isEmpty()) {
            pendingExpenseKeyDao.deleteAll()

            return
        }

        pendingExpenseKeyDao.deleteNotIn(expenseUuids = unopened.map { it.uuid })

        val firstSeenAt = nowEpochMilliseconds()

        unopened.forEach { expense ->
            pendingExpenseKeyDao.insertNew(
                expenseUuid = expense.uuid,
                creditorUuid = expense.creditor_uuid,
                firstSeenAt = firstSeenAt,
            )
        }
    }

    /** Marks as verified the keys the expenses they name have just opened under. */
    private suspend fun promoteVerifiedKeys(opened: List<ExpenseDto>, held: Map<String, HeldKey>) {
        opened.forEach { expense ->
            val key = held[expense.uuid] ?: return@forEach

            if (key.isVerified) return@forEach

            keyRepository.markVerified(record = key.record)
        }
    }

}

private fun DirectSendError.toSyncError(): OneTimeExpenseSyncError =
    when (this) {
        is DirectSendError.Channel -> when (val channelError = error) {
            is DirectChannelError.Identity -> OneTimeExpenseSyncError.Identity(channelError.error)
            DirectChannelError.Storage -> OneTimeExpenseSyncError.Storage
            is DirectChannelError.Network -> OneTimeExpenseSyncError.Network(channelError.error)
            else -> OneTimeExpenseSyncError.Storage
        }

        DirectSendError.Storage, DirectSendError.GroupUnusable -> OneTimeExpenseSyncError.Storage

        is DirectSendError.Network -> OneTimeExpenseSyncError.Network(error)

        DirectSendError.RecipientGone, DirectSendError.RateLimited -> OneTimeExpenseSyncError.Storage
    }

private fun ExpenseDto.hasCanonicalUuids(): Boolean =
    uuid.isCanonicalUuid() &&
            creditor_uuid.isCanonicalUuid() &&
            beneficiaries.all { it.user_uuid.isCanonicalUuid() }

/** Whether this is a UUID written the one way the server and the app write one. */
internal fun String.isCanonicalUuid(): Boolean = Uuid.parseHexDashOrNull(this)?.toString() == this

/** How many creditors are asked in one sync, so a re-login does not ask everyone at once. */
private const val MAX_CREDITORS_PER_SYNC = 3

/** How long to wait between asking the same creditor again, doubling, up to a day. */
private fun backoffFor(requestCount: Int): Long {
    val doublings = minOf(requestCount, MAX_BACKOFF_DOUBLINGS)

    return minOf(BASE_BACKOFF_MILLISECONDS shl doublings, MAX_BACKOFF_MILLISECONDS)
}

private const val BASE_BACKOFF_MILLISECONDS = 5L * 60 * 1000
private const val MAX_BACKOFF_MILLISECONDS = 24L * 60 * 60 * 1000
private const val MAX_BACKOFF_DOUBLINGS = 10
