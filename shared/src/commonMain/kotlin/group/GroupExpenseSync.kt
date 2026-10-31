package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.ListedGroupExpenseDao
import database.data.local.dao.StoredGroupExpense
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupExpenseDeletionEntity
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
 * What a [GroupExpenseSync] pass changed of a group expense as this device shows it, its version,
 * its deletion flag or both, once for both, as much of it as telling the user what changed takes: an
 * expense new to this device when [previous] is null, and otherwise one edited, deleted or
 * restored, by [authorUuid], [expense] and [previous] saying which.
 */
class GroupExpenseChange(
    val groupUuid: String,

    /** The expense as this device shows it from now on, deleted or not as its flag says. */
    val expense: ExpenseWithBeneficiaries,

    /** The revision the version shown was signed at, which a deletion or a restore leaves as it was. */
    val revision: Long,

    /**
     * Who made the change, as it is signed: the member whose flag deleted or restored the expense
     * when the pass changed whether it is deleted, and otherwise whoever wrote the version stored,
     * see [ExpenseWithBeneficiaries.creditorUuid] for who paid.
     */
    val authorUuid: String,

    /** The expense as this device showed it before the pass, or null when it showed none. */
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
    /**
     * Every expense whose version the pass stored, or whose deletion flag changed whether it is
     * deleted, once each, whatever else it came to, in no particular order.
     */
    val changes: List<GroupExpenseChange>,

    /**
     * The revision the server lists each expense at that this pass refused the version of, by UUID:
     * one that does not open as that expense's, nobody this device holds a key for signed, says
     * what the app does not write, or may not follow the version shown, see
     * [GroupExpenseOpening.Refused] and [database.data.local.dao.ExpenseDao.storeGroupExpenses].
     * Never one this device cannot read yet, nor one it could not tell this time, nor one that
     * opened but was not stored for the flag listed with it, of an expense it shows no version of
     * yet, see [GroupExpenseSync]: every member's device takes that version, and a write goes over
     * none such, see [GroupExpenseWriter].
     */
    val refused: Map<String, Long>,

    /** The first thing that kept the pass from doing all it had to, or null when nothing did. */
    val failure: GroupExpenseSyncError?,
)

/**
 * Brings the expenses of the user's groups on this device in line with what the server holds:
 * every group the user is a current member of, after the group sync has read what waits in them,
 * see [GroupSync], so that the key of every epoch a Commit it read led to is held. Never a group the user left or
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
 * Each deletion flag listed is opened the same way, see [GroupExpenseOpener.openDeletion], and
 * checked against a version of its expense, which tells whether its author may set it on a
 * settlement: of an expense this device shows, once the versions are stored, against the one now
 * shown, the one the pass stored or one before, and stored if it is newer than the flag of its
 * expense this device shows, see [database.data.local.dao.ExpenseDao.storeGroupExpenseDeletions],
 * a flag refused leaving the one shown in place, or none. One sealed under a key this device cannot
 * have yet keeps the group from being taken in, as a version does.
 *
 * Of an expense this device shows no version of yet, as after the user joined the group, or a
 * log-out, the version opened is stored only together with the flag listed with it, if there is
 * one, checked against that very version first, and in the same transaction, see
 * [database.data.local.dao.ExpenseDao.storeGroupExpensesWithDeletions], so that the first the
 * device shows of the expense is deleted or not as the flag says, whatever cuts the pass short, and
 * no later pass tells its flag as news. Neither is stored when it refuses the flag, as when every
 * member's device refuses a version, and the expense is kept as not read yet, see
 * [database.data.local.entity.UnreadableGroupExpenseEntity], while the flag is sealed under a key
 * it cannot have yet: the version alone would show the expense as though nobody deleted it,
 * counted in the balances of this device alone, a deleted settlement among them, which nobody may
 * restore. The server keeps the newest flag of an expense alone, though, and cannot tell one that
 * verifies, so a member can still keep an expense from showing on a device that never showed it by
 * writing a flag nobody can verify over the one shown elsewhere, as they can by writing a version
 * nobody can verify; a device that shows it keeps what it shows.
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
    private val keyOf: suspend (
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ) -> Result<ByteArray, GroupKeyChainError>,
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

        val shown = expenseDao.getGroupExpenseRevisions(groupUuid = group.uuid).associateBy { it.uuid }

        val opener = GroupExpenseOpener(
            group = group,
            selfUuid = selfUuid,
            client = client,
            keyOf = keyOf,
            recorded = groupDao::getMemberSignatureKey,
        )

        val opened = mutableListOf<OpenedGroupExpense>()

        // The flags of the expenses this device shows no version of yet, stored with their
        // versions, and of those it shows, stored after them.
        val deletionsOfNew = mutableListOf<GroupExpenseDeletionEntity>()
        val deletions = mutableListOf<GroupExpenseDeletionEntity>()

        val unreadable = linkedSetOf<String>()

        listed.forEach { entry ->
            // The version shown, or one before it, which would not be stored over it: only what is
            // new is opened, so that a pass over a group of a thousand expenses opens what changed.
            if (entry.revision <= (shown[entry.uuid]?.revision ?: 0L)) return@forEach

            val version = when (
                val opening = opener.open(
                    expenseUuid = entry.uuid,
                    revision = entry.revision,
                    keyGeneration = entry.keyGeneration,
                    content = entry.content,
                )
            ) {
                is GroupExpenseOpening.Opened -> opening.opened

                GroupExpenseOpening.Unreadable -> {
                    unreadable += entry.uuid

                    return@forEach
                }

                GroupExpenseOpening.Refused -> {
                    refused[entry.uuid] = entry.revision

                    return@forEach
                }

                GroupExpenseOpening.Failed -> return@forEach
            }

            val deletion = entry.deletion

            // Of an expense this device shows, the flag is checked against the version shown once
            // the versions are stored, below.
            if (entry.uuid in shown || deletion == null) {
                opened += version

                return@forEach
            }

            // Of one it shows no version of yet, the version is stored only together with the flag,
            // checked against that very version, see GroupExpenseSync: neither is when the flag is
            // refused, nor until it can be read.
            when (
                val opening = opener.openDeletion(
                    expenseUuid = entry.uuid,
                    deletionRevision = deletion.deletionRevision,
                    keyGeneration = deletion.keyGeneration,
                    content = deletion.content,
                    shown = version.toEntity(),
                )
            ) {
                is GroupExpenseOpening.Opened -> {
                    opened += version
                    deletionsOfNew += opening.opened
                }

                GroupExpenseOpening.Unreadable -> unreadable += entry.uuid
                GroupExpenseOpening.Refused, GroupExpenseOpening.Failed -> Unit
            }
        }

        // In one transaction with the flags of the expenses new to this device, see
        // GroupExpenseSync: a version stored alone shows its expense as though nobody deleted it
        // until its flag is stored, and until the next pass should this one be cut short in between.
        val stored = expenseDao.storeGroupExpensesWithDeletions(
            groupUuid = group.uuid,
            expenses = opened.map { it.toEntity() },
            deletions = deletionsOfNew,
        )
        val storedUuids = stored.versions.mapTo(HashSet()) { it.expense.expense.uuid }

        opened.filter { it.expense.uuid !in storedUuids }.forEach { refused[it.expense.uuid] = it.revision }

        listed.forEach { entry ->
            val deletion = entry.deletion ?: return@forEach

            // Of an expense new to this device: opened with its version, above.
            val shownRevisions = shown[entry.uuid] ?: return@forEach

            // As for a version: only a flag newer than the one shown is opened.
            if (deletion.deletionRevision <= shownRevisions.deletionRevision) return@forEach

            // The one this pass stored, or the one before: gone only should a log-out have emptied
            // the database meanwhile, which leaves nothing to store the flag on.
            val version = expenseDao.getGroupExpense(groupUuid = group.uuid, uuid = entry.uuid) ?: return@forEach

            when (
                val opening = opener.openDeletion(
                    expenseUuid = entry.uuid,
                    deletionRevision = deletion.deletionRevision,
                    keyGeneration = deletion.keyGeneration,
                    content = deletion.content,
                    shown = version,
                )
            ) {
                is GroupExpenseOpening.Opened -> deletions += opening.opened
                GroupExpenseOpening.Unreadable -> unreadable += entry.uuid
                GroupExpenseOpening.Refused, GroupExpenseOpening.Failed -> Unit
            }
        }

        val flagged = stored.deletions + expenseDao.storeGroupExpenseDeletions(groupUuid = group.uuid, deletions = deletions)

        listedDao.replaceUnreadable(groupUuid = group.uuid, expenseUuids = unreadable.toList())

        changes += changesOf(
            groupUuid = group.uuid,
            stored = stored.versions,
            flagged = flagged,
            isFirstListing = isFirstListing,
        )

        val failure = when (val failed = opener.failure) {
            is GroupExpenseOpener.Failure.Network -> GroupExpenseSyncError.Network(failed.error)
            GroupExpenseOpener.Failure.Storage -> GroupExpenseSyncError.Storage
            null -> null
        }

        // Only once nothing is left over that a later pass would store as news: a version or a flag
        // that could not be told this time, or read yet, comes from before the user's view as much
        // as the rest does.
        if (isFirstListing && failure == null && unreadable.isEmpty()) groupDao.markExpensesTakenIn(uuid = group.uuid)

        return failure
    }

    /**
     * What a pass over the group [groupUuid] changed of each expense, once each, out of the versions
     * it [stored] and the flags it [flagged], which it stored after them: shown as the last stored
     * left it, next to what showed before the first did. Of the author of the flag when it changed
     * whether the expense is deleted, which is what changed for the user's balances, and of the
     * version otherwise: an expense new to this device is added by whoever wrote it, deleted or not.
     * A flag that leaves the expense deleted, or not, as it was, and no version with it, changed
     * nothing shown.
     */
    private fun changesOf(
        groupUuid: String,
        stored: List<StoredGroupExpense>,
        flagged: List<StoredGroupExpense>,
        isFirstListing: Boolean,
    ): List<GroupExpenseChange> {
        val versions = stored.associateBy { it.expense.expense.uuid }
        val flags = flagged.associateBy { it.expense.expense.uuid }

        return (versions.keys + flags.keys).mapNotNull { uuid ->
            val version = versions[uuid]
            val flag = flags[uuid]

            val previous = if (version != null) version.previous else flag?.previous
            val now = checkNotNull(flag ?: version).expense

            val isFlagged = previous != null && previous.expense.isDeleted != now.expense.isDeleted

            if (version == null && !isFlagged) return@mapNotNull null

            GroupExpenseChange(
                groupUuid = groupUuid,
                expense = now.toExpenseWithBeneficiaries(),
                revision = checkNotNull(now.expense.revision),
                authorUuid = if (isFlagged) checkNotNull(now.deletion).authorUuid else checkNotNull(now.expense.authorUuid),
                previous = previous?.toExpenseWithBeneficiaries(),
                isFirstListing = isFirstListing,
            )
        }
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
