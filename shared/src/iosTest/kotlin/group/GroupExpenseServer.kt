package group

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uniffi.lib_duckcount.MlsClient
import util.NetworkError
import util.Result
import kotlin.test.assertNotNull

/** The currency every group these tests hold is in. */
internal val GROUP_CURRENCY = CurrencyCatalog.defaultCurrency

private val listingJson = Json { encodeDefaults = true }

/** When the server takes a version a test gives no time for, as the API writes every time it serves. */
private const val REVISED_AT = "2026-10-30T19:45:00.000Z"

/**
 * An expense of the group [groupUuid] paid by [creditorUuid], [amount] split between [shares],
 * each fixed or not: what a member's form makes of one.
 */
internal fun groupExpenseOf(
    uuid: String,
    groupUuid: String,
    creditorUuid: String,
    amount: String,
    shares: List<Pair<String, String>>,
    title: String = "Dinner at the harbour",
    description: String = "Fish, wine and the tip",
    timestamp: String = "2026-10-30T19:45:00",
    isSettlement: Boolean = false,
) = ExpenseWithBeneficiaries(
    uuid = uuid,
    title = title,
    timestamp = timestamp,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = GROUP_CURRENCY,
    groupUuid = groupUuid,
    beneficiaries = shares.map { (userUuid, share) ->
        Beneficiary(expenseUuid = uuid, userUuid = userUuid, share = share, isShareFixed = false)
    },
)

/**
 * This expense as [revision] of its group, written by [author], the user [authorUuid], sealed under
 * [groupKey], of the generation [keyGeneration], as the server lists it once [author]'s device sent
 * it, see [sealGroupExpense].
 */
internal fun ExpenseWithBeneficiaries.listedAs(
    revision: Long,
    author: MlsClient,
    authorUuid: String,
    groupKey: ByteArray,
    keyGeneration: Long = FIRST_KEY_GENERATION,
) = ListedGroupExpenseDto(
    uuid = uuid,
    revision = revision,
    keyGeneration = keyGeneration,
    content = assertNotNull(
        sealGroupExpense(
            groupUuid = checkNotNull(groupUuid),
            revision = revision,
            groupKey = groupKey,
            client = author,
            authorUuid = authorUuid,
        ),
    ),
    deletion = null,
)

/**
 * The deletion flag of this expense saying [isDeleted], at [deletionRevision], set by [author], the
 * user [authorUuid], sealed under [groupKey], of the generation [keyGeneration], as the server lists
 * it once [author]'s device sent it, see [sealGroupExpenseDeletion].
 */
internal fun ExpenseWithBeneficiaries.flaggedAs(
    deletionRevision: Long,
    isDeleted: Boolean,
    author: MlsClient,
    authorUuid: String,
    groupKey: ByteArray,
    keyGeneration: Long = FIRST_KEY_GENERATION,
) = ListedGroupExpenseDeletionDto(
    deletionRevision = deletionRevision,
    keyGeneration = keyGeneration,
    content = assertNotNull(
        sealGroupExpenseDeletion(
            groupUuid = checkNotNull(groupUuid),
            expenseUuid = uuid,
            deletionRevision = deletionRevision,
            isDeleted = isDeleted,
            groupKey = groupKey,
            client = author,
            authorUuid = authorUuid,
        ),
    ),
)

/**
 * What the server holds of the expenses of one group, and serves as the API does: the listing,
 * newest first, each expense with its deletion flag, and its feed of changes, each on one page or
 * [pageSize] at a time, the feed made by the first listing and answering `409` for any token until
 * then or once it [restartFeed]s, each expense's history, the version it stands at and every one
 * its updates replaced, each with when it was taken, and the writes, taken as the API takes them,
 * in the order it checks them, a create's UUID derived from its nonce as [uuidsByNonce] says the
 * caller's device drew it, an update refused once the expense is at its last revision.
 */
internal class GroupExpenseServer {

    /** The generation of the group's key every write has to be sealed under. */
    var keyGeneration = FIRST_KEY_GENERATION

    /** The version each expense stands at, by UUID, in the order the creates were taken. */
    val expenses = linkedMapOf<String, ListedGroupExpenseDto>()

    /** When the server took the version each expense stands at, by UUID. */
    private val takenAt = mutableMapOf<String, String>()

    /** The versions each expense's updates replaced, newest first, each with when the server took it. */
    val history = mutableMapOf<String, MutableList<GroupExpenseHistoryVersionDto>>()

    /** The UUID each nonce derives, as the caller's device drew the two. */
    val uuidsByNonce = mutableMapOf<String, String>()

    /** Every write sent, in order. */
    val creates = mutableListOf<CreateGroupExpenseBody>()
    val updates = mutableListOf<UpdateGroupExpenseBody>()
    val deletions = mutableListOf<SetGroupExpenseDeletionBody>()

    /** How many expenses a page of the listing, or changes a page of the feed, holds at most; null for every one on one. */
    var pageSize: Int? = null

    /** How many times the listing, a later page of it, and its changes, were asked for. */
    var listings = 0
    var laterPages = 0
    var changeReads = 0

    /** Whether the server stores the next write, and the answer to it never reaches the caller. */
    var losesNextAnswer = false

    /** Whether the server can be reached at all. */
    var isReachable = true

    /** The UUIDs of the expenses changed, in the order they changed: the feed, until it starts over. */
    private val changes = mutableListOf<String>()
    private var hasFeed = false

    /** Has the feed start over, so that the next changes asked for answer `409`. */
    fun restartFeed() {
        hasFeed = false
        changes.clear()
    }

    /**
     * Takes [version] as the one its expense stands at, at [revisedAt], the one before it kept in
     * its history, its deletion flag kept as it was, and records it in the feed.
     */
    fun put(version: ListedGroupExpenseDto, revisedAt: String = REVISED_AT) {
        val replaced = expenses[version.uuid]

        replaced?.let {
            history.getOrPut(version.uuid) { mutableListOf() }.add(0, it.asVersion(revisedAt = takenAt.getValue(version.uuid)))
        }

        expenses[version.uuid] = version.copy(deletion = replaced?.deletion)
        takenAt[version.uuid] = revisedAt
        changes += version.uuid
    }

    /** Takes [deletion] as the deletion flag of the expense [expenseUuid], and records it in the feed. */
    fun putDeletion(expenseUuid: String, deletion: ListedGroupExpenseDeletionDto) {
        expenses[expenseUuid] = expenses.getValue(expenseUuid).copy(deletion = deletion)
        changes += expenseUuid
    }

    fun getExpenses(): Result<ListGroupExpensesResponse, NetworkError> {
        if (!isReachable) return Result.Error(NetworkError.UNREACHABLE)

        listings++

        if (!hasFeed) {
            hasFeed = true
            changes.clear()
        }

        val (page, nextCursor) = listingFrom(position = 0)

        return Result.Success(ListGroupExpensesResponse(expenses = page, nextCursor = nextCursor, feedToken = "t${changes.size}"))
    }

    /** The page of the listing [cursor], the `next_cursor` of the page before, starts. */
    fun getExpensesFrom(cursor: String): Result<ListGroupExpensesPage, NetworkError> {
        if (!isReachable) return Result.Error(NetworkError.UNREACHABLE)

        laterPages++

        val (page, nextCursor) = listingFrom(position = cursor.removePrefix("c").toInt())

        return Result.Success(ListGroupExpensesPage(expenses = page, nextCursor = nextCursor))
    }

    fun getChanges(since: String): Result<ListGroupExpenseChangesResponse, NetworkError> {
        if (!isReachable) return Result.Error(NetworkError.UNREACHABLE)

        changeReads++

        val position = since.removePrefix("t").toInt()

        if (!hasFeed || position > changes.size) return Result.Error(NetworkError.CONFLICT)

        // What it hands back is acknowledged, and forgotten.
        repeat(position) { changes.removeAt(0) }

        val page = changes.take(pageSize ?: changes.size)

        return Result.Success(
            ListGroupExpenseChangesResponse(
                expenses = page.distinct().mapNotNull { expenses[it] }.map { it.toJson() },
                feedToken = "t${page.size}",
                hasMore = page.size < changes.size,
            ),
        )
    }

    fun create(body: CreateGroupExpenseBody): GroupExpenseWriteAnswer {
        creates += body

        if (!isReachable) return GroupExpenseWriteAnswer.Failed(NetworkError.UNREACHABLE)

        if (body.keyGeneration != keyGeneration) return GroupExpenseWriteAnswer.KeyGenerationNotCurrent(keyGeneration)

        val uuid = uuidsByNonce.getValue(body.nonce)

        if (uuid in expenses) return GroupExpenseWriteAnswer.AlreadyExists

        put(
            ListedGroupExpenseDto(
                uuid = uuid,
                revision = 1,
                keyGeneration = body.keyGeneration,
                content = body.content,
                deletion = null,
            ),
        )

        return answered()
    }

    fun update(expenseUuid: String, body: UpdateGroupExpenseBody): GroupExpenseWriteAnswer {
        updates += body

        if (!isReachable) return GroupExpenseWriteAnswer.Failed(NetworkError.UNREACHABLE)

        if (body.keyGeneration != keyGeneration) return GroupExpenseWriteAnswer.KeyGenerationNotCurrent(keyGeneration)

        // An update is only ever of an expense the caller's device shows, which the server took.
        val stored = expenses.getValue(expenseUuid)

        if (stored.revision == LAST_GROUP_EXPENSE_REVISION) return GroupExpenseWriteAnswer.EditLimitReached

        if (body.revision != stored.revision + 1) return GroupExpenseWriteAnswer.RevisionNotNext(stored.revision)

        put(
            ListedGroupExpenseDto(
                uuid = expenseUuid,
                revision = body.revision,
                keyGeneration = body.keyGeneration,
                content = body.content,
                deletion = null,
            ),
        )

        return answered()
    }

    fun setDeletion(expenseUuid: String, body: SetGroupExpenseDeletionBody): GroupExpenseDeletionAnswer {
        deletions += body

        if (!isReachable) return GroupExpenseWriteAnswer.Failed(NetworkError.UNREACHABLE)

        if (body.keyGeneration != keyGeneration) return GroupExpenseWriteAnswer.KeyGenerationNotCurrent(keyGeneration)

        // As for an update: only ever of an expense the caller's device shows.
        val stored = expenses.getValue(expenseUuid).deletion?.deletionRevision ?: 0L

        if (body.deletionRevision != stored + 1) return GroupExpenseDeletionAnswer.DeletionRevisionNotNext(stored)

        putDeletion(
            expenseUuid = expenseUuid,
            deletion = ListedGroupExpenseDeletionDto(
                deletionRevision = body.deletionRevision,
                keyGeneration = body.keyGeneration,
                content = body.content,
            ),
        )

        return if (losesNextAnswer) {
            losesNextAnswer = false

            GroupExpenseWriteAnswer.Failed(NetworkError.REQUEST_TIMEOUT)
        } else {
            GroupExpenseWriteAnswer.Written
        }
    }

    /** The version the expense [expenseUuid] stands at, and every one its updates replaced. */
    fun history(expenseUuid: String): Result<GroupExpenseHistoryResponse, NetworkError> {
        val current = expenses[expenseUuid] ?: return Result.Error(NetworkError.NOT_FOUND)

        return Result.Success(
            GroupExpenseHistoryResponse(
                current = current.asVersion(revisedAt = takenAt.getValue(expenseUuid)),
                previous = history[expenseUuid].orEmpty(),
            ),
        )
    }

    /** The page of the listing from the expense at [position] on, and the cursor of the next one, if any. */
    private fun listingFrom(position: Int): Pair<List<JsonObject>, String?> {
        val listing = expenses.values.reversed()
        val end = minOf(listing.size, position + (pageSize ?: listing.size))

        return listing.subList(position, end).map { it.toJson() } to "c$end".takeIf { end < listing.size }
    }

    /** The answer to a write the server stored. */
    private fun answered(): GroupExpenseWriteAnswer =
        if (losesNextAnswer) {
            losesNextAnswer = false

            GroupExpenseWriteAnswer.Failed(NetworkError.REQUEST_TIMEOUT)
        } else {
            GroupExpenseWriteAnswer.Written
        }

    private fun ListedGroupExpenseDto.toJson() =
        listingJson.encodeToJsonElement(ListedGroupExpenseDto.serializer(), this).jsonObject

    /** This version as the history serves it, taken at [revisedAt]. */
    private fun ListedGroupExpenseDto.asVersion(revisedAt: String) = GroupExpenseHistoryVersionDto(
        revision = revision,
        keyGeneration = keyGeneration,
        content = content,
        revisedAt = revisedAt,
    )
}
