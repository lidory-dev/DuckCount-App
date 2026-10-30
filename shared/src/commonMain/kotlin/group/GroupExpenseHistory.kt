package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.domain.ExpenseWithBeneficiaries
import kotlinx.datetime.LocalDateTime
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.MlsClient
import util.Error
import util.NetworkError
import util.Result

/**
 * One version of a group expense as its history tells it: what it said, which revision it is, who
 * wrote it, whether it is the expense as it was created, and, for that one, when, see
 * [GroupExpenseHistory].
 */
class GroupExpenseHistoryEntry(
    val expense: ExpenseWithBeneficiaries,

    /** The revision its author signed it at, which no other version in its history has. */
    val revision: Long,

    /** Who wrote it, as they signed it: any member may write any version but a settlement's. */
    val authorUuid: String,

    /** Whether this is the expense as it was created, see [FIRST_GROUP_EXPENSE_REVISION]. */
    val isCreation: Boolean,

    /**
     * For the version created, the expense's own date, as its author's clock read it, written with
     * no time zone; null for any other: the server says nothing of when an update was made.
     */
    val madeAt: LocalDateTime?,
)

/** Why the history of a group expense could not be read, see [GroupExpenseHistory]. */
sealed interface GroupExpenseHistoryError : Error {

    /** Nobody is signed in, so there is nobody to read the history as. */
    data object NoSession : GroupExpenseHistoryError

    /** This device shows no version of such an expense of a group. */
    data object NotShown : GroupExpenseHistoryError

    /**
     * The user is no longer in the expense's group, removed or left, and the server gives its
     * expenses' histories to its current members alone: the version shown is all there is.
     */
    data object NotMember : GroupExpenseHistoryError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupExpenseHistoryError

    /** The user's own storage failed as the key pinned for an author was read. */
    data object Storage : GroupExpenseHistoryError

    /**
     * The server could not be reached, or refused the call, for the history or for the links back to
     * the key one of its versions is sealed under.
     */
    data class Network(val error: NetworkError) : GroupExpenseHistoryError
}

/**
 * Reads the history of an expense of one of the user's groups: the version this device shows, then
 * each one from before it that the server keeps, the nine its updates last replaced, newest first.
 *
 * Each earlier version is opened and checked as a listed one is, see [GroupExpenseOpener]: under
 * its own generation of the group's key, signed by whoever wrote it, which the history shows, and
 * saying what the app writes. One that does not open, or that nobody this device holds a key for
 * signed, is left out, and so is one sealed under a key this device cannot have yet. In the order
 * of the revisions they were signed at, and not the server's, which nothing vouches for, each once,
 * and none from after the version shown: the server answers with what it holds now, which can be
 * ahead of what this device has caught up with.
 *
 * Read from the server every time, as a group's information's is, see [GroupHistory], and under no
 * lock, as it writes nothing but the keys the chain leads back to, see [GroupKeyChain].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [shown] is [ExpenseDao.getWithBeneficiaries], [group]
 * is [GroupDao.get], [client] is [MlsIdentityRepository.client], [history] is
 * [GroupExpenseApi.history], [keyOf] is [GroupKeyChain.keyOf] and [recorded] is
 * [GroupDao.getMemberSignatureKey], or what a test stands in for any of them.
 */
class GroupExpenseHistory internal constructor(
    private val userUuid: () -> String?,
    private val shown: suspend (expenseUuid: String) -> ExpenseWithBeneficiariesEntity?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val history: suspend (
        groupUuid: String,
        expenseUuid: String,
    ) -> Result<List<GroupExpenseHistoryVersionDto>, NetworkError>,
    private val keyOf: suspend (groupUuid: String, generation: Long) -> Result<ByteArray, GroupKeyChainError>,
    private val recorded: suspend (groupUuid: String, userUuid: String) -> ByteArray?,
) {
    constructor(
        groupExpenseApi: GroupExpenseApi,
        groupKeyChain: GroupKeyChain,
        groupDao: GroupDao,
        expenseDao: ExpenseDao,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        shown = expenseDao::getWithBeneficiaries,
        group = groupDao::get,
        client = mlsIdentityRepository::client,
        history = groupExpenseApi::history,
        keyOf = groupKeyChain::keyOf,
        recorded = groupDao::getMemberSignatureKey,
    )

    /** The versions of the group expense [expenseUuid] its history shows, see [GroupExpenseHistory]. */
    suspend fun versions(expenseUuid: String): Result<List<GroupExpenseHistoryEntry>, GroupExpenseHistoryError> {
        val selfUuid = userUuid() ?: return Result.Error(GroupExpenseHistoryError.NoSession)

        val shown = shown(expenseUuid) ?: return Result.Error(GroupExpenseHistoryError.NotShown)
        val groupUuid = shown.expense.groupUuid ?: return Result.Error(GroupExpenseHistoryError.NotShown)
        val shownRevision = shown.expense.revision ?: return Result.Error(GroupExpenseHistoryError.NotShown)
        val shownAuthor = shown.expense.authorUuid ?: return Result.Error(GroupExpenseHistoryError.NotShown)

        val held = group(groupUuid)?.takeIf { !it.isLeft } ?: return Result.Error(GroupExpenseHistoryError.NotMember)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupExpenseHistoryError.Identity(opened.error))
        }

        val listed = when (val read = history(groupUuid, expenseUuid)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(GroupExpenseHistoryError.Network(read.error))
        }

        val opener = GroupExpenseOpener(
            group = held,
            selfUuid = selfUuid,
            client = client,
            keyOf = keyOf,
            recorded = recorded,
        )

        val earlier = listed
            .filter { it.revision < shownRevision }
            .mapNotNull { version ->
                val opening = opener.open(
                    expenseUuid = expenseUuid,
                    revision = version.revision,
                    keyGeneration = version.keyGeneration,
                    content = version.content,
                )

                (opening as? GroupExpenseOpening.Opened)?.version
            }
            .sortedByDescending { it.revision }
            .distinctBy { it.revision }

        when (val failure = opener.failure) {
            is GroupExpenseOpener.Failure.Network -> return Result.Error(GroupExpenseHistoryError.Network(failure.error))
            GroupExpenseOpener.Failure.Storage -> return Result.Error(GroupExpenseHistoryError.Storage)
            null -> Unit
        }

        val versions = listOf(
            OpenedGroupExpense(expense = shown.toExpenseWithBeneficiaries(), revision = shownRevision, authorUuid = shownAuthor),
        ) + earlier

        return Result.Success(
            versions.map { version ->
                val isCreation = version.revision == FIRST_GROUP_EXPENSE_REVISION

                GroupExpenseHistoryEntry(
                    expense = version.expense,
                    revision = version.revision,
                    authorUuid = version.authorUuid,
                    isCreation = isCreation,
                    madeAt = if (isCreation) LocalDateTime.Formats.ISO.parseOrNull(version.expense.timestamp) else null,
                )
            },
        )
    }
}
