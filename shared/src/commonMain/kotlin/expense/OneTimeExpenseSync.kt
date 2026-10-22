package expense

import authenticationUtils.getAuthenticatedUserUuid
import channel.DirectChannelError
import database.data.local.dao.ExpenseDao
import database.data.local.dao.ListedOneTimeExpenseDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.ListedOneTimeExpenseEntity
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val listedDao: ListedOneTimeExpenseDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val keyRepository: OneTimeExpenseKeyRepository,
    private val expenseKeyDelivery: ExpenseKeyDelivery,
    private val expenseKeyPull: ExpenseKeyPull,
    private val directMessageSync: DirectMessageSync,
) {
    /**
     * Serializes [refresh], which a push and the list being opened can both reach at once. A
     * read that finished after a newer one would act on what its own, older listing did not
     * name, and drop what the newer one had just written down as waiting.
     */
    private val refreshMutex = Mutex()

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
     * The listing is kept on the device, see [ListedOneTimeExpenseEntity]: read whole once
     * and brought up to date with what changed from then on, see [readListing], so that a
     * sync fetches what changed rather than everything the user is on. What it does with
     * the listing is not cut down with it: everything below works from all of it, as it
     * would from a listing read whole just now, which it is as of where the feed has got
     * to: what it does not name is deleted, forgotten or no longer waited for.
     *
     * Sends no message: what is missing is written down here and asked for in
     * [requestMissingKeys], so that the request loop is not run from inside the
     * transaction that stores what it is about.
     */
    suspend fun refresh(): EmptyResult<OneTimeExpenseSyncError> = refreshMutex.withLock {
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

        // Read before the listing is brought up to date. A sync running alongside this one can
        // store a key while it is, to an expense created since, which it could not show.
        val unverified = keyRepository.unverifiedExpenseUuids()

        // The same for the rows: the user's own expense, stored once the server took it, can
        // be created while the listing is brought up to date and miss it. Only what was there
        // before goes for not being in it.
        val stored = expenseDao.getOneTimeExpenseUuids()

        val expenses = when (val listed = readListing()) {
            // Only entries whose UUIDs are written as the server writes them, whether or not
            // they open. These UUIDs end up in rows, in key requests and in the library, which
            // throws on a string that is not valid UTF-16 rather than refusing it. An expense's
            // UUID also keys its row in the list.
            is Result.Success -> listed.data.filter { it.hasCanonicalUuids() }
            is Result.Error -> return@withLock Result.Error(OneTimeExpenseSyncError.Network(listed.error))
        }

        val listedUuids = expenses.mapTo(HashSet()) { it.uuid }

        // A key held unverified to an expense the user is not on can never be checked, and its
        // nonce shows whose expense it would be, not that there is one: anybody who can reach
        // the user can send them as many keys to expenses of theirs that were never created
        // as they like. A creditor hands a key over only once the server names the user on
        // the expense, so one that arrived before this listing was asked for names an expense
        // it shows, unless the user has been taken off it or it has been deleted since, which
        // leaves the key nothing to open either. Or the server left it out, which costs no
        // more than withholding it: listed again, it does not open, and its creditor is asked.
        keyRepository.forgetUnverified(expenseUuids = unverified - listedUuids)

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
            goneUuids = stored.filterNot { it in listedUuids },
        )

        // Whether each was paid back, as its creditor signed it with the key its version
        // opened under, and only for an expense that did open: it is their word on an
        // expense this device shows. Kept apart from the rows, see
        // [database.data.local.entity.OneTimeExpenseSettledMarkEntity].
        expenseDao.storeSettledMarks(
            marks = opened.mapNotNull { expense ->
                held[expense.uuid]?.let { expense.openSettledMark(record = it.record) }?.toEntity()
            },
        )

        reconcilePending(unopened = unopened)

        // A key that arrived before its expense did, or before this device could check it,
        // is verified only once the expense it names has opened under it, signed by the
        // creditor it came from.
        promoteVerifiedKeys(opened = opened, held = held)

        // Done after the rows are stored, because it is one request per key, and after the
        // promotion, so that a key verified just now goes up with this listing.
        keyRepository.backUp(expenses = expenses)

        Result.Success(Unit)
    }

    /**
     * The server's listing as the device keeps it, brought up to date, or the first failure,
     * see [refresh].
     *
     * Read whole when the device keeps none, and otherwise brought up to date with what
     * changed since, see [followChanges]. Read whole again when the server can no longer say
     * what changed since the token kept, its `409`: the feed started over, because too much
     * waited in it, the session went a week without a refresh, or the server changed what it
     * writes of an expense; or another device of the account read past the token since.
     * Read whole again, too, when what the device keeps no longer reads as an
     * [ExpenseDto]: this build expects something of an expense that the server did not
     * write when it was kept, and will not write again until it changes. Something the
     * server only starts writing reaches what is kept when the server starts the feed
     * over, see `ListedOneTimeExpense` in the API.
     */
    private suspend fun readListing(): Result<List<ExpenseDto>, NetworkError> {
        val feedToken = listedDao.getFeedToken() ?: return readWholeListing()

        when (val followed = followChanges(since = feedToken)) {
            is Result.Success -> keptListing()?.let { return Result.Success(it) }

            is Result.Error -> if (followed.error != NetworkError.CONFLICT) {
                return Result.Error(followed.error)
            }
        }

        listedDao.reset()

        return readWholeListing()
    }

    /**
     * Reads the listing whole, a page at a time, and keeps it in place of what was kept,
     * with where its first page says the feed stood; or returns the first failure and keeps
     * nothing.
     *
     * The server pages on something no edit changes, so an expense listed all the while
     * comes exactly once, however the others change between pages. What changes while they
     * are read is in the feed after the first page's token, whichever pages it did or did
     * not make it into, and is followed at once, see [followChanges]: handed over, the token
     * also has the server forget what the listing covers, removals included, rather than
     * keep it until the next sync. That following failing fails nothing: the listing is
     * kept, and the next sync hands the token over again.
     */
    private suspend fun readWholeListing(): Result<List<ExpenseDto>, NetworkError> {
        val first = when (val listed = expenseApi.getOneTimeExpenses()) {
            is Result.Success -> listed.data
            is Result.Error -> return Result.Error(listed.error)
        }

        val entries = first.expenses.readEntries()?.toMutableList()
            ?: return Result.Error(NetworkError.SERIALIZATION)

        var cursor = first.next_cursor

        while (cursor != null) {
            val page = when (val listed = expenseApi.getOneTimeExpenses(cursor = cursor)) {
                is Result.Success -> listed.data
                is Result.Error -> return Result.Error(listed.error)
            }

            entries += page.expenses.readEntries() ?: return Result.Error(NetworkError.SERIALIZATION)

            cursor = page.next_cursor
        }

        listedDao.replaceAll(expenses = entries.map { it.second }, feedToken = first.feed_token)

        if (followChanges(since = first.feed_token) is Result.Success) {
            keptListing()?.let { return Result.Success(it) }
        }

        return Result.Success(entries.map { it.first })
    }

    /**
     * Applies what changed in the listing since [since] to what the device keeps of it, a
     * page at a time and each with where it leaves the feed, or returns the first failure.
     * A page that fails leaves those before it applied, and the next sync carries on from
     * the last of them.
     *
     * Each token handed over tells the server the device has everything before it, and the
     * server forgets it: only a token stored with what it covers is ever handed over. The
     * last page's is handed back once more when the sync brought anything, so that the
     * server forgets what it brought, removals included, now rather than at the next sync.
     * Once only: what changes meanwhile waits for the next sync either way. A failure of that
     * request fails nothing, everything being applied already: the next sync hands the token
     * over again.
     */
    private suspend fun followChanges(since: String): EmptyResult<NetworkError> {
        var feedToken = since
        var confirm = false
        var confirmed = false

        do {
            val page = when (val changes = expenseApi.getOneTimeExpenseChanges(since = feedToken)) {
                is Result.Success -> changes.data
                is Result.Error -> return if (confirm) Result.Success(Unit) else Result.Error(changes.error)
            }

            val changed = page.expenses.readEntries() ?: return Result.Error(NetworkError.SERIALIZATION)

            listedDao.apply(
                changed = changed.map { it.second },
                removedUuids = page.removed,
                feedToken = page.feed_token,
            )

            feedToken = page.feed_token

            confirm = !page.has_more && !confirmed && (changed.isNotEmpty() || page.removed.isNotEmpty())
            confirmed = confirmed || confirm
        } while (page.has_more || confirm)

        return Result.Success(Unit)
    }

    /** What the device keeps of the listing, or null when any of it no longer reads, see [readListing]. */
    private suspend fun keptListing(): List<ExpenseDto>? =
        listedDao.getAll().map { kept -> kept.json.toExpenseDto() ?: return null }

    /**
     * Asks each creditor for the keys this device is still missing from them, see
     * [ExpenseKeyPull].
     */
    suspend fun requestMissingKeys(): EmptyResult<OneTimeExpenseSyncError> {
        val ownUserUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseSyncError.NoSession)

        return expenseKeyPull.requestMissingKeys(ownUserUuid = ownUserUuid)
    }

    /**
     * Hands over the keys to the user's own expenses that somebody named on them has not
     * been handed yet, once the expense, as this device shows it, names them, see
     * [ExpenseKeyDelivery].
     */
    suspend fun shareOwedKeys(): EmptyResult<OneTimeExpenseSyncError> {
        val ownUserUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseSyncError.NoSession)

        return expenseKeyDelivery.handOver(ownUserUuid = ownUserUuid)
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

/** The same failure, told the way a one-time expense sync tells it. */
internal fun DirectSendError.toSyncError(): OneTimeExpenseSyncError =
    when (this) {
        is DirectSendError.Channel -> when (val channelError = error) {
            is DirectChannelError.Identity -> OneTimeExpenseSyncError.Identity(channelError.error)
            DirectChannelError.Storage -> OneTimeExpenseSyncError.Storage
            is DirectChannelError.Network -> OneTimeExpenseSyncError.Network(channelError.error)
            else -> OneTimeExpenseSyncError.Storage
        }

        DirectSendError.Storage, DirectSendError.GroupUnusable -> OneTimeExpenseSyncError.Storage

        is DirectSendError.Network -> OneTimeExpenseSyncError.Network(error)

        DirectSendError.RecipientDeleted, is DirectSendError.RateLimited -> OneTimeExpenseSyncError.Storage
    }

/** Reads the listing's entries as this build knows them, ignoring what it does not. */
private val listingJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.toExpenseDto(): ExpenseDto? =
    runCatching { listingJson.decodeFromJsonElement(ExpenseDto.serializer(), this) }.getOrNull()

/**
 * A page's entries as this build reads them, each with what to keep of it, the JSON the
 * server wrote; or null when any of them does not read, which a build that decoded the
 * pages whole would have failed on as well.
 */
private fun List<JsonObject>.readEntries(): List<Pair<ExpenseDto, ListedOneTimeExpenseEntity>>? =
    map { json ->
        val expense = json.toExpenseDto() ?: return null

        expense to ListedOneTimeExpenseEntity(expenseUuid = expense.uuid, json = json.toString())
    }

private fun String.toExpenseDto(): ExpenseDto? =
    runCatching { listingJson.decodeFromString(ExpenseDto.serializer(), this) }.getOrNull()

private fun ExpenseDto.hasCanonicalUuids(): Boolean =
    uuid.isCanonicalUuid() &&
            creditor_uuid.isCanonicalUuid() &&
            beneficiaries.all { it.user_uuid.isCanonicalUuid() }

/** Whether this is a UUID written the one way the server and the app write one. */
internal fun String.isCanonicalUuid(): Boolean = Uuid.parseHexDashOrNull(this)?.toString() == this
