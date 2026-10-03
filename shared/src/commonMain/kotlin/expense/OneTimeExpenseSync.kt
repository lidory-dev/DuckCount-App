package expense

import authenticationUtils.getAuthenticatedUserUuid
import channel.DirectChannelError
import database.data.local.dao.ExpenseDao
import database.data.local.dao.OwedExpenseKeyDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.PendingExpenseKeyState
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.DirectMessageKindDto
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageSender
import message.DirectMessageSync
import message.DirectSendError
import util.EmptyResult
import util.NetworkError
import util.Result
import util.nowEpochMilliseconds
import kotlin.uuid.Uuid

/**
 * Brings the user's one-time expenses up to date, and chases the keys to the ones this
 * device cannot open.
 *
 * The server tells every participant that an expense exists and who else is on it; only its
 * key says what it is for or what it costs. So a sync reads what the server has and opens
 * what can be opened, then asks about the rest.
 *
 * Asking is driven by what is missing rather than by what arrived, which is what makes it
 * cover more than the case it was written for. A key message that was never sent, one that
 * was lost, and one that reached a device that had logged out and could never be read all
 * look the same from here: an expense the user is on, with no key for it.
 *
 * The other direction is the keys the user owes: the ones to their own expenses that
 * somebody named on them has not been handed yet, which a sync hands over in between, see
 * [shareOwedKeys].
 */
class OneTimeExpenseSync(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val owedExpenseKeyDao: OwedExpenseKeyDao,
    private val keyRepository: OneTimeExpenseKeyRepository,
    private val directMessageSender: DirectMessageSender,
    private val directMessageSync: DirectMessageSync,
) {
    /**
     * Serializes [shareOwedKeys], which an expense being written and a sync can both reach
     * at once. Each would read the same keys as still owed, and hand them over twice.
     */
    private val shareMutex = Mutex()

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

        when (val shared = shareOwedKeys()) {
            is Result.Success -> Unit
            is Result.Error -> failure = failure ?: shared.error
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
        // Not left to the push that carried it either. A push can be missed (the device
        // was off, the distributor dropped the subscription, a log-out took it with it),
        // and a key that was only ever going to arrive that way would never arrive at all,
        // leaving an expense invisible with nothing to bring it back. Opening the list is
        // the other moment the user is plainly asking to be up to date.
        //
        // Its failure is not reported: whatever was not read stays on the server, and the
        // expenses below are worth reading either way.
        directMessageSync.sync()

        // Read before the listing is asked for. A sync running alongside this one can store a
        // key while it is in flight, to an expense created since, which it could not show.
        val unverified = keyRepository.unverifiedExpenseUuids()

        val expenses = when (val listed = expenseApi.getOneTimeExpenses()) {
            // Only entries whose UUIDs are written as the server writes them, whether or not
            // they open. These UUIDs end up in rows, in key requests and in the library, which
            // throws on a string that is not valid UTF-16 rather than refusing it. An expense's
            // UUID also keys its row in the list.
            is Result.Success -> listed.data.filter { it.hasCanonicalUuids() }
            is Result.Error -> return Result.Error(OneTimeExpenseSyncError.Network(listed.error))
        }

        // A key held unverified to an expense the user is not on can never be checked, and its
        // nonce shows whose expense it would be, not that there is one: anybody who can reach
        // the user can send them as many keys to expenses of theirs that were never created
        // as they like. A creditor hands a key over only once the server names the user on
        // the expense, so one that arrived before this listing was asked for names an expense
        // it shows, unless the user has been taken off it or it has been deleted since, which
        // leaves the key nothing to open either. Or the server left it out, which costs no
        // more than withholding it: listed again, it does not open, and its creditor is asked.
        keyRepository.forgetUnverified(expenseUuids = unverified - expenses.mapTo(HashSet()) { it.uuid })

        // Before deciding what is missing: an expense whose key was merely not brought back
        // yet looks exactly like one whose key never arrived, and would be asked for.
        keyRepository.restore(expenses = expenses)

        val held = keyRepository.keysFor(expenses = expenses)

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

                    // Nobody this device has pinned, so nobody it can ask: a former contact,
                    // or one a log-out made it lose track of. No retry changes that, so the
                    // rows wait until they are pinned, which adding them again does.
                    DirectSendError.Channel(DirectChannelError.NotPinned) -> Unit

                    // Somebody whose identity is in doubt, who is not asked for anything until
                    // that is settled, which no retry does.
                    DirectSendError.Channel(DirectChannelError.IdentityChanged) -> Unit

                    // Sending more only pushes the count further past the limit. The rest
                    // wait for the next sync.
                    DirectSendError.RateLimited -> return Result.Error(
                        OneTimeExpenseSyncError.Network(NetworkError.TOO_MANY_REQUESTS),
                    )

                    else -> failure = failure ?: request.error.toSyncError()
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Hands over the keys to the user's own expenses that somebody named on them has not
     * been handed yet, see [database.data.local.entity.OwedExpenseKeyEntity], once the
     * expense, as this device shows it, names them.
     *
     * Each key goes only to somebody the creditor's own record names, see [keyFor], and not
     * on the strength of the expense's rows alone: those are refilled from the server.
     *
     * One message per person, and one person at a time: the server counts messages against an
     * allowance, and running past it only pushes the count further out, so the first refusal
     * stops the pass and the rest wait for the next one. A message carries no more keys than
     * a recipient reads of one, see [ExpenseKeyShare.MAX_KEYS], and the rest wait the same way.
     */
    suspend fun shareOwedKeys(): EmptyResult<OneTimeExpenseSyncError> = shareMutex.withLock {
        val ownUserUuid = getAuthenticatedUserUuid()
            ?: return@withLock Result.Error(OneTimeExpenseSyncError.NoSession)

        val owed = owedExpenseKeyDao.getListed()

        if (owed.isEmpty()) return@withLock Result.Success(Unit)

        var failure: OneTimeExpenseSyncError? = null

        for ((recipientUuid, rows) in owed.groupBy { it.recipientUuid }) {
            val keys = rows.mapNotNull { row ->
                keyRepository.record(expenseUuid = row.expenseUuid)
                    ?.keyFor(requesterUuid = recipientUuid, ownUserUuid = ownUserUuid)
            }.take(ExpenseKeyShare.MAX_KEYS)

            if (keys.isEmpty()) continue

            val expenseUuids = keys.map { it.expenseUuid }

            val sent = directMessageSender.send(
                recipientUserUuid = recipientUuid,
                payload = ExpenseKeyShare(keys = keys).serialize(),
                // So a locked phone can say an expense arrived rather than nothing.
                kind = DirectMessageKindDto.ONE_TIME_EXPENSE,
            )

            when (sent) {
                is Result.Success -> owedExpenseKeyDao.delete(
                    recipientUuid = recipientUuid,
                    expenseUuids = expenseUuids,
                )

                is Result.Error -> when (sent.error) {
                    // Nobody to hand the key to: their account is gone, or the server offers
                    // no way to reach them that this build can use. If they come back, they
                    // ask for the key, and the record answers them.
                    DirectSendError.RecipientGone,
                    DirectSendError.Channel(DirectChannelError.Unreachable),
                        -> owedExpenseKeyDao.delete(
                        recipientUuid = recipientUuid,
                        expenseUuids = expenseUuids,
                    )

                    // Somebody this device no longer has pinned, or whose identity is in
                    // doubt. No retry changes either, so the keys wait until that does.
                    DirectSendError.Channel(DirectChannelError.NotPinned),
                    DirectSendError.Channel(DirectChannelError.IdentityChanged),
                        -> Unit

                    DirectSendError.RateLimited -> return@withLock Result.Error(
                        OneTimeExpenseSyncError.Network(NetworkError.TOO_MANY_REQUESTS),
                    )

                    else -> failure = failure ?: sent.error.toSyncError()
                }
            }
        }

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
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
