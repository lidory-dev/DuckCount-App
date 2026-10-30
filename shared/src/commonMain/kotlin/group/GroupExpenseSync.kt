package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.ListedGroupExpenseDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.ListedGroupExpenseEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.domain.ExpenseWithBeneficiaries
import expense.isCanonicalUuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.MlsClient
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * A version of a group expense a [GroupExpenseSync] pass stored, as much of it as telling the user
 * what changed takes: an expense new to this device when [previous] is null, and otherwise one
 * edited, deleted or restored, by [authorUuid], [expense] and [previous] saying which.
 */
class GroupExpenseChange(
    val groupUuid: String,

    /** The version stored, as this device shows it from now on. */
    val expense: ExpenseWithBeneficiaries,

    /** The revision it was signed at. */
    val revision: Long,

    /** Who wrote it, as it is signed, see [ExpenseWithBeneficiaries.creditorUuid] for who paid. */
    val authorUuid: String,

    /** The version it replaced on this device, or null when it showed none. */
    val previous: ExpenseWithBeneficiaries?,

    /**
     * Whether this device had yet to take in the group's expenses whole, see
     * [GroupEntity.hasTakenInExpenses], as after it joined the group, or a log-out: every expense of
     * the group is then new to it, and none of them news to the user.
     */
    val isFirstListing: Boolean,
)

/** What one [GroupExpenseSync.sync], or [GroupExpenseSync.syncGroup], came to. */
class GroupExpenseSyncPass(
    /** Every version the pass stored, whatever else it came to, in no particular order. */
    val changes: List<GroupExpenseChange>,

    /**
     * The revision the server lists each expense at that this pass refused the version of, by UUID:
     * one that does not open as that expense's, nobody this device holds a key for signed, says
     * what the app does not write, or may not follow the version shown, see
     * [GroupExpenseOpening.Refused] and [database.data.local.dao.ExpenseDao.storeGroupExpenses].
     * Never one this device cannot read yet, nor one it could not tell this time.
     */
    val refused: Map<String, Long>,

    /** The first thing that kept the pass from doing all it had to, or null when nothing did. */
    val failure: GroupExpenseSyncError?,
)

/**
 * Brings the expenses of the user's groups on this device in line with what the server holds:
 * every group the user is a current member of, after the group sync has read what waits in them,
 * see [GroupSync], so that the key of a rotation it read is held. Never a group the user left or
 * was removed from: the server lists its expenses to its current members alone, and this device
 * keeps what it had of them, read-only, see [GroupEntity.isLeft]. Nor one whose creation the server
 * has yet to take, which holds none.
 *
 * Each group's listing is kept on the device, see [ListedGroupExpenseEntity], as the one-time
 * expenses' is, see [expense.OneTimeExpenseSync]: read whole once, with where it leaves the
 * group's feed of changes, then brought up to date with what changed from then on, and read whole
 * again whenever the server can no longer say what changed, its `409`.
 *
 * Each version listed is opened, see [GroupExpenseOpener]: under the generation of the group's key
 * it is sealed under, through the chain of links when this device lacks it, see [GroupKeyChain],
 * checked against the key of whoever signed it, see [authorKeyOf], and for saying what the app
 * writes, see [opened]. One opened is stored if it is newer than any version of its expense this
 * device has shown, and may follow the one it replaces, see
 * [database.data.local.dao.ExpenseDao.storeGroupExpenses]: a version refused leaves the one shown in
 * place. One sealed under a key this device cannot have yet is kept as not read yet, see
 * [database.data.local.entity.UnreadableGroupExpenseEntity], and tried again on every pass, the
 * listing holding it; nothing else of the listing is opened again once this device shows it.
 *
 * Under no lock but its own: reading what is stored of a group's keys, and storing the ones the
 * chain led back to, is all it does of a group, see [GroupKeyChain], and the group sync holds the
 * groups' lock across its requests to the server, which a screen opening a group would wait on.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [client] is [MlsIdentityRepository.client],
 * [listExpenses], [listExpensesFrom] and [listChanges] are [GroupExpenseApi.getExpenses], first page
 * and later ones, and [GroupExpenseApi.getChanges], and [keyOf] is [GroupKeyChain.keyOf], or what a
 * test stands in for any of them.
 */
class GroupExpenseSync internal constructor(
    private val userUuid: () -> String?,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val listExpenses: suspend (groupUuid: String) -> Result<ListGroupExpensesResponse, NetworkError>,
    private val listExpensesFrom: suspend (
        groupUuid: String,
        cursor: String,
    ) -> Result<ListGroupExpensesPage, NetworkError>,
    private val listChanges: suspend (
        groupUuid: String,
        since: String,
    ) -> Result<ListGroupExpenseChangesResponse, NetworkError>,
    private val keyOf: suspend (groupUuid: String, generation: Long) -> Result<ByteArray, GroupKeyChainError>,
    private val groupDao: GroupDao,
    private val expenseDao: ExpenseDao,
    private val listedDao: ListedGroupExpenseDao,
) {
    constructor(
        groupExpenseApi: GroupExpenseApi,
        groupKeyChain: GroupKeyChain,
        groupDao: GroupDao,
        expenseDao: ExpenseDao,
        listedGroupExpenseDao: ListedGroupExpenseDao,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        client = mlsIdentityRepository::client,
        listExpenses = groupExpenseApi::getExpenses,
        listExpensesFrom = groupExpenseApi::getExpenses,
        listChanges = groupExpenseApi::getChanges,
        keyOf = groupKeyChain::keyOf,
        groupDao = groupDao,
        expenseDao = expenseDao,
        listedDao = listedGroupExpenseDao,
    )

    /**
     * Serializes the passes, which a push, a screen opening a group and a write hearing that the
     * expense changed meanwhile can all set off at once: a pass storing a listing older than the
     * one another just stored would hand the server a token behind the one already handed over.
     */
    private val mutex = Mutex()

    /**
     * Brings the expenses of every group the user is a current member of up to date, see
     * [GroupExpenseSync], and says what it stored and what failed first: a group that fails is left
     * for the next pass, and the next group is seen to all the same.
     */
    suspend fun sync(): GroupExpenseSyncPass = mutex.withLock {
        pass(selfUuid = userUuid()) { groupDao.getAll() }
    }

    /**
     * Brings the expenses of the group [groupUuid] up to date, see [sync], as a screen opening it
     * asks: nothing, for a group the user is no longer in.
     */
    suspend fun syncGroup(groupUuid: String): GroupExpenseSyncPass = mutex.withLock {
        pass(selfUuid = userUuid()) { listOfNotNull(groupDao.get(uuid = groupUuid)) }
    }

    /**
     * Brings the expenses of the group [groupUuid] up to date for the user [selfUuid], see
     * [syncGroup], as a write hearing that the expense changed meanwhile asks, see
     * [GroupExpenseWriter]: it read [selfUuid] before it sent anything, and the device may have
     * locked as it waited for the answer, leaving the user's UUID unreadable, see
     * [getAuthenticatedUserUuid]. Nothing else the pass reads needs the device unlocked, the user's
     * identity being open already for the write.
     */
    internal suspend fun syncGroup(groupUuid: String, selfUuid: String): GroupExpenseSyncPass = mutex.withLock {
        pass(selfUuid = selfUuid) { listOfNotNull(groupDao.get(uuid = groupUuid)) }
    }

    /** One pass, for the user [selfUuid], over those of [groups] they are a current member of, see [sync]. */
    private suspend fun pass(selfUuid: String?, groups: suspend () -> List<GroupEntity>): GroupExpenseSyncPass {
        if (selfUuid == null) {
            return GroupExpenseSyncPass(changes = emptyList(), refused = emptyMap(), failure = GroupExpenseSyncError.NoSession)
        }

        val current = groups().filter { !it.isLeft && !groupDao.hasPendingCreation(groupUuid = it.uuid) }

        // Nothing to open the user's identity for, which reads the vault from the server until it
        // first opens in the process.
        if (current.isEmpty()) return GroupExpenseSyncPass(changes = emptyList(), refused = emptyMap(), failure = null)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data

            is Result.Error -> return GroupExpenseSyncPass(
                changes = emptyList(),
                refused = emptyMap(),
                failure = GroupExpenseSyncError.Identity(opened.error),
            )
        }

        val changes = mutableListOf<GroupExpenseChange>()
        val refused = mutableMapOf<String, Long>()
        var failure: GroupExpenseSyncError? = null

        current.forEach { group ->
            bringUpToDate(group = group, selfUuid = selfUuid, client = client, changes = changes, refused = refused)
                ?.let { failure = failure ?: it }
        }

        return GroupExpenseSyncPass(changes = changes.toList(), refused = refused.toMap(), failure = failure)
    }

    /**
     * Reads the listing of [group]'s expenses, opens what is new in it, stores what opened, notes
     * in [changes] what it stored and in [refused] what it refused, and records what could not be
     * read yet, see [GroupExpenseSync]; or returns the first failure. A pass that leaves nothing of
     * the listing to tell, or to read later, has taken the group's expenses in, see
     * [GroupEntity.hasTakenInExpenses].
     */
    private suspend fun bringUpToDate(
        group: GroupEntity,
        selfUuid: String,
        client: MlsClient,
        changes: MutableList<GroupExpenseChange>,
        refused: MutableMap<String, Long>,
    ): GroupExpenseSyncError? {
        val isFirstListing = !group.hasTakenInExpenses

        val listed = when (val read = readListing(groupUuid = group.uuid)) {
            // Only entries whose UUID is written as the server writes one: it ends up in the
            // library, which throws on a string that is not valid UTF-16, and keys the row.
            is Result.Success -> read.data.filter { it.uuid.isCanonicalUuid() }
            is Result.Error -> return GroupExpenseSyncError.Network(read.error)
        }

        val shown = expenseDao.getGroupExpenseRevisions(groupUuid = group.uuid).associate { it.uuid to it.revision }

        val opener = GroupExpenseOpener(
            group = group,
            selfUuid = selfUuid,
            client = client,
            keyOf = keyOf,
            recorded = groupDao::getMemberSignatureKey,
        )

        val opened = mutableListOf<OpenedGroupExpense>()
        val unreadable = mutableListOf<String>()

        listed.forEach { entry ->
            // The version shown, or one before it, which would not be stored over it: only what is
            // new is opened, so that a pass over a group of a thousand expenses opens what changed.
            if (entry.revision <= (shown[entry.uuid] ?: 0L)) return@forEach

            when (
                val opening = opener.open(
                    expenseUuid = entry.uuid,
                    revision = entry.revision,
                    keyGeneration = entry.keyGeneration,
                    content = entry.content,
                )
            ) {
                is GroupExpenseOpening.Opened -> opened += opening.version
                GroupExpenseOpening.Unreadable -> unreadable += entry.uuid
                GroupExpenseOpening.Refused -> refused[entry.uuid] = entry.revision
                GroupExpenseOpening.Failed -> Unit
            }
        }

        val stored = expenseDao.storeGroupExpenses(groupUuid = group.uuid, expenses = opened.map { it.toEntity() })
        val storedUuids = stored.mapTo(HashSet()) { it.expense.expense.uuid }

        opened.filter { it.expense.uuid !in storedUuids }.forEach { refused[it.expense.uuid] = it.revision }

        listedDao.replaceUnreadable(groupUuid = group.uuid, expenseUuids = unreadable)

        stored.forEach { version ->
            changes += GroupExpenseChange(
                groupUuid = group.uuid,
                expense = version.expense.toExpenseWithBeneficiaries(),
                revision = checkNotNull(version.expense.expense.revision),
                authorUuid = checkNotNull(version.expense.expense.authorUuid),
                previous = version.previous?.toExpenseWithBeneficiaries(),
                isFirstListing = isFirstListing,
            )
        }

        val failure = when (val failed = opener.failure) {
            is GroupExpenseOpener.Failure.Network -> GroupExpenseSyncError.Network(failed.error)
            GroupExpenseOpener.Failure.Storage -> GroupExpenseSyncError.Storage
            null -> null
        }

        // Only once nothing is left over that a later pass would store as news: a version that
        // could not be told this time, or read yet, comes from before the user's view as much as
        // the rest does.
        if (isFirstListing && failure == null && unreadable.isEmpty()) groupDao.markExpensesTakenIn(uuid = group.uuid)

        return failure
    }

    /**
     * The server's listing of the expenses of the group [groupUuid] as the device keeps it, brought
     * up to date, or the first failure, as for one-time expenses, see
     * [expense.OneTimeExpenseSync]: read whole when the device keeps none, otherwise brought up to
     * date with what changed since, see [followChanges], and read whole again on the server's
     * `409`, or when what the device keeps no longer reads as a [ListedGroupExpenseDto]. The first
     * time the user lists a group, or the first since they were added back to it, the server makes
     * their feed of it, and a token from before answers that `409` too.
     */
    private suspend fun readListing(groupUuid: String): Result<List<ListedGroupExpenseDto>, NetworkError> {
        val feedToken = listedDao.getFeedToken(groupUuid = groupUuid) ?: return readWholeListing(groupUuid)

        when (val followed = followChanges(groupUuid = groupUuid, since = feedToken)) {
            is Result.Success -> keptListing(groupUuid = groupUuid)?.let { return Result.Success(it) }

            is Result.Error -> if (followed.error != NetworkError.CONFLICT) {
                return Result.Error(followed.error)
            }
        }

        listedDao.reset(groupUuid = groupUuid)

        return readWholeListing(groupUuid)
    }

    /**
     * Reads the listing of the expenses of the group [groupUuid] whole, a page at a time, and keeps
     * it in place of what was kept, with where its first page says the group's feed stood; or
     * returns the first failure and keeps nothing. Each page is one snapshot, and the server pages
     * on when it took each create, which nothing changes, so an expense comes once however the
     * others change between pages: what changes as they are read is in the feed after the first
     * page's token, followed at once, as for one-time expenses.
     */
    private suspend fun readWholeListing(groupUuid: String): Result<List<ListedGroupExpenseDto>, NetworkError> {
        val first = when (val listed = listExpenses(groupUuid)) {
            is Result.Success -> listed.data
            is Result.Error -> return Result.Error(listed.error)
        }

        val entries = first.expenses.readEntries(groupUuid = groupUuid)?.toMutableList()
            ?: return Result.Error(NetworkError.SERIALIZATION)

        var cursor = first.nextCursor

        while (cursor != null) {
            val page = when (val listed = listExpensesFrom(groupUuid, cursor)) {
                is Result.Success -> listed.data
                is Result.Error -> return Result.Error(listed.error)
            }

            entries += page.expenses.readEntries(groupUuid = groupUuid) ?: return Result.Error(NetworkError.SERIALIZATION)

            cursor = page.nextCursor
        }

        listedDao.replaceAll(groupUuid = groupUuid, expenses = entries.map { it.second }, feedToken = first.feedToken)

        if (followChanges(groupUuid = groupUuid, since = first.feedToken) is Result.Success) {
            keptListing(groupUuid = groupUuid)?.let { return Result.Success(it) }
        }

        return Result.Success(entries.map { it.first })
    }

    /**
     * Applies what changed in the listing of the expenses of the group [groupUuid] since [since] to
     * what the device keeps of it, a page at a time and each with where it leaves the feed, or
     * returns the first failure, exactly as for one-time expenses: only a token stored with what it
     * covers is ever handed over, and the last is handed back once more when the pass brought
     * anything, so that the server forgets what it brought now rather than at the next pass.
     */
    private suspend fun followChanges(groupUuid: String, since: String): EmptyResult<NetworkError> {
        var feedToken = since
        var confirm = false
        var confirmed = false

        do {
            val page = when (val changes = listChanges(groupUuid, feedToken)) {
                is Result.Success -> changes.data
                is Result.Error -> return if (confirm) Result.Success(Unit) else Result.Error(changes.error)
            }

            val changed = page.expenses.readEntries(groupUuid = groupUuid) ?: return Result.Error(NetworkError.SERIALIZATION)

            listedDao.apply(groupUuid = groupUuid, changed = changed.map { it.second }, feedToken = page.feedToken)

            feedToken = page.feedToken

            confirm = !page.hasMore && !confirmed && changed.isNotEmpty()
            confirmed = confirmed || confirm
        } while (page.hasMore || confirm)

        return Result.Success(Unit)
    }

    /** What the device keeps of the listing of the group [groupUuid], or null when any of it no longer reads. */
    private suspend fun keptListing(groupUuid: String): List<ListedGroupExpenseDto>? =
        listedDao.getAll(groupUuid = groupUuid).map { kept -> kept.json.toListedDto() ?: return null }
}

/** Reads the listing's entries as this build knows them, ignoring what it does not. */
private val listingJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.toListedDto(): ListedGroupExpenseDto? =
    runCatching { listingJson.decodeFromJsonElement(ListedGroupExpenseDto.serializer(), this) }.getOrNull()

private fun String.toListedDto(): ListedGroupExpenseDto? =
    runCatching { listingJson.decodeFromString(ListedGroupExpenseDto.serializer(), this) }.getOrNull()

/**
 * A page's entries as this build reads them, each with what to keep of it for the group
 * [groupUuid], the JSON the server wrote; or null when any of them does not read, which a build that
 * decoded the pages whole would have failed on as well.
 */
private fun List<JsonObject>.readEntries(groupUuid: String): List<Pair<ListedGroupExpenseDto, ListedGroupExpenseEntity>>? =
    map { json ->
        val expense = json.toListedDto() ?: return null

        expense to ListedGroupExpenseEntity(groupUuid = groupUuid, expenseUuid = expense.uuid, json = json.toString())
    }
