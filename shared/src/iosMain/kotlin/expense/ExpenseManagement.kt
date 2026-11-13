package expense

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.ExpenseLimits
import app.lidory.duckcount.validateExpense
import authentication.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUuid
import database.data.repository.ExpenseRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import database.domain.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import sync.ForegroundSync
import util.Result
import app.lidory.duckcount.computeBeneficiaries as computeShares
import uniffi.lib_duckcount.Currency as LibraryCurrency

/**
 * What the iOS expense screens run on the user's expenses, one-time and in their groups, with the
 * same repositories and in the same order as Android's `ExpenseListCallBacksImpl`,
 * `ExpenseDetailCallBacksImpl`, `CreateExpenseCallBacksImpl` and `ExpenseHistoryCallBacksImpl`,
 * which live in the Android app and branch on the expense's group as this does, and with their
 * failures told as those tell them, see [ExpenseWriteFailure].
 *
 * Nothing here throws: on iOS, a Kotlin exception reaching Swift ends the app, and the group expense
 * writer and history let the keychain's [IllegalStateException] through once the device locks.
 */
class ExpenseManagement(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val foregroundSync: ForegroundSync,
    private val networkConnectivity: NetworkConnectivity,
) {

    val maxTitleLength: Int = ExpenseLimits.MAX_TITLE_LENGTH

    val maxDescriptionLength: Int = ExpenseLimits.MAX_DESCRIPTION_LENGTH

    /** The most beneficiaries an expense may list, which the server enforces too. */
    val maxBeneficiaries: Int = ExpenseLimits.MAX_BENEFICIARIES

    /** The currencies a one-time expense can be created with. */
    val currencies: List<Currency> = CurrencyCatalog.defaultCurrencies

    /** The currency a new one-time expense starts in. */
    val defaultCurrency: Currency = CurrencyCatalog.defaultCurrency

    fun authenticatedUserUuid(): String? = guarded("authenticatedUserUuid", null) { getAuthenticatedUserUuid() }

    /** The most edits a group expense takes after its creation. */
    val maxGroupExpenseEdits: Int = ExpenseLimits.MAX_GROUP_EXPENSE_EDITS

    /** Localized, human-readable name for an ISO 4217 currency [code]. */
    fun currencyDisplayName(code: String): String = database.domain.currencyDisplayName(code)

    /**
     * The one-time expenses this device shows, as it changes, newest first, and two made at the
     * same time by UUID, as Android's paged list orders them, see `ExpenseDao.pagingSource`.
     */
    fun expenses(): Flow<List<ExpenseWithBeneficiaries>> =
        expenseRepository.getAllWithoutGroup().map { expenses ->
            expenses.sortedWith(compareByDescending<ExpenseWithBeneficiaries> { it.timestamp }.thenBy { it.uuid })
        }

    /** The expense [uuid] as this device shows it, as it changes, or null once it is gone. */
    fun expense(uuid: String): Flow<ExpenseWithBeneficiaries?> = expenseRepository.getWithBeneficiaries(uuid = uuid)

    /**
     * Brings the expenses up to date with the server, and says whether the one-time expenses'
     * listing was read: what Android's list does as it opens, after what a push makes its device do,
     * iOS receiving none yet, see [ForegroundSync].
     */
    suspend fun refresh(): Boolean = foregroundSync.sync().isOneTimeListingRead

    /** The group [groupUuid]'s expenses, deleted ones included, newest first, see [ExpenseRepository.getAllFromGroup]. */
    fun groupExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> =
        expenseRepository.getAllFromGroup(groupUuid = groupUuid)

    /** Brings the group [groupUuid] and its expenses up to date, see [ForegroundSync.syncGroup]. */
    suspend fun refreshGroup(groupUuid: String) {
        foregroundSync.syncGroup(groupUuid = groupUuid)
    }

    /**
     * The currency of the group [groupUuid], or null once there is no such group on the device. Read
     * with its members: `GroupRepository.get` waits forever for a group the device no longer holds,
     * and reading them lazily keeps a locked device from throwing as the flow is asked for.
     */
    fun groupCurrency(groupUuid: String): Flow<Currency?> =
        flow { emitAll(groupRepository.getWithMembers(uuid = groupUuid).map { it?.currency }) }
            .catch { failure -> if (failure !is IllegalStateException) throw failure }

    /** Whether the user is no longer a member of the group [groupUuid], as it changes. */
    fun isGroupLeft(groupUuid: String): Flow<Boolean> = groupRepository.isLeft(uuid = groupUuid)

    /**
     * The members of the group [groupUuid] whose name contains [filter], whom an expense of it can be
     * paid by and name, see [UserRepository.getAllFromGroupFiltered].
     */
    fun groupUsers(filter: String, groupUuid: String): Flow<List<User>> =
        userRepository.getAllFromGroupFiltered(filter = filter, groupUuid = groupUuid)

    /**
     * The name to show for each of [userUuids] that this device holds one for, as they change: one
     * missing is somebody it holds no profile for, which a one-time expense makes ordinary, the
     * server asking for no relationship between a creditor and the people they name.
     */
    fun fullNames(userUuids: List<String>): Flow<Map<String, String>> {
        val distinct = userUuids.distinct()

        if (distinct.isEmpty()) return flowOf(emptyMap())

        val names = distinct.map { uuid -> userRepository.getDisplayName(uuid).map { name -> uuid to name } }

        return combine(names) { pairs -> pairs.mapNotNull { (uuid, name) -> name?.let { uuid to it } }.toMap() }
            .distinctUntilChanged()
    }

    /** Who a one-time expense can name, whose name contains [filter], see [UserRepository.getAllFiltered]. */
    fun users(filter: String): Flow<List<User>> = userRepository.getAllFiltered(filter = filter)

    /**
     * The shares of [beneficiaryUserUuids] of [amount], as the library works them out from the
     * shares fixed as amounts, percentages and weights, in the order given, which decides who gets
     * the cents that do not divide evenly. Each comes back unfixed and with no expense: the form
     * says which it fixed.
     */
    fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedShares: Map<String, String>,
        sharePercentages: Map<String, String>,
        shareWeights: Map<String, String>,
    ): List<Beneficiary> =
        computeShares(
            amount = amount,
            currency = LibraryCurrency(name = currency.name, symbol = currency.symbol, hasSubUnit = currency.hasSubunits),
            beneficiaryUserUuids = beneficiaryUserUuids,
            fixedShares = fixedShares,
            sharePercentages = sharePercentages,
            shareWeights = shareWeights,
        ).map {
            Beneficiary(
                expenseUuid = "",
                userUuid = it.userUuid,
                share = it.share,
                isShareFixed = false,
                sharePercentage = it.sharePercentage,
                shareWeight = it.shareWeight,
            )
        }

    /** Why the library refuses [amount] shared as [beneficiaries] say, or null when it takes it. */
    fun validate(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): ExpenseError? =
        validateExpense(amount = amount, currency = currency, beneficiaries = beneficiaries)

    /**
     * Creates [expense] on the server first, see [ExpenseRepository.createOneTimeExpense], or in its
     * group, see [ExpenseRepository.createGroupExpense]: null once it is written, or why it is not.
     */
    suspend fun create(expense: ExpenseWithBeneficiaries): ExpenseSaveFailure? {
        val groupUuid = expense.groupUuid

        if (groupUuid != null) {
            return guarded("create", ExpenseSaveFailure.Write(ExpenseWriteFailure.UNKNOWN)) {
                when (val result = expenseRepository.createGroupExpense(groupUuid = groupUuid, expenseWithBeneficiaries = expense)) {
                    is Result.Success -> null
                    is Result.Error -> result.error.toSaveFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

        return when (val result = expenseRepository.createOneTimeExpense(expenseWithBeneficiaries = expense)) {
            is Result.Success -> null
            is Result.Error -> result.error.toSaveFailure()
        }
    }

    /**
     * Writes [expense] over the one it edits, see [ExpenseRepository.updateOneTimeExpense], or in
     * its group, see [ExpenseRepository.updateGroupExpense].
     */
    suspend fun update(expense: ExpenseWithBeneficiaries): ExpenseSaveFailure? {
        val groupUuid = expense.groupUuid

        if (groupUuid != null) {
            return guarded("update", ExpenseSaveFailure.Write(ExpenseWriteFailure.UNKNOWN)) {
                when (val result = expenseRepository.updateGroupExpense(groupUuid = groupUuid, expenseWithBeneficiaries = expense)) {
                    is Result.Success -> null
                    is Result.Error -> result.error.toSaveFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

        return when (val result = expenseRepository.updateOneTimeExpense(expenseWithBeneficiaries = expense)) {
            is Result.Success -> null
            is Result.Error -> result.error.toSaveFailure()
        }
    }

    /**
     * Deletes the expense [uuid], on the server first, see [ExpenseRepository.deleteOneTimeExpense],
     * or [ExpenseRepository.deleteGroupExpense] for one in a group, which any member can restore.
     */
    suspend fun delete(uuid: String): ExpenseWriteFailure? {
        val groupUuid = groupOf(uuid)

        if (groupUuid != null) {
            return guarded("delete", ExpenseWriteFailure.UNKNOWN) {
                when (val result = expenseRepository.deleteGroupExpense(groupUuid = groupUuid, uuid = uuid)) {
                    is Result.Success -> null
                    is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

        return when (val result = expenseRepository.deleteOneTimeExpense(uuid = uuid)) {
            is Result.Success -> null
            is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
        }
    }

    /**
     * Restores the deleted group expense [uuid], see [ExpenseRepository.restoreGroupExpense]: null once
     * the server took it, and for a one-time expense, which nothing restores, as Android's detail does.
     */
    suspend fun restore(uuid: String): ExpenseWriteFailure? {
        val groupUuid = groupOf(uuid) ?: return null

        return guarded("restore", ExpenseWriteFailure.UNKNOWN) {
            when (val result = expenseRepository.restoreGroupExpense(groupUuid = groupUuid, uuid = uuid)) {
                is Result.Success -> null
                is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    /** Marks the user's expense [uuid] as paid back, or no longer, see [ExpenseRepository.setOneTimeExpenseSettled]. */
    suspend fun setSettled(uuid: String, isSettled: Boolean): ExpenseWriteFailure? =
        when (val result = expenseRepository.setOneTimeExpenseSettled(uuid = uuid, isSettled = isSettled)) {
            is Result.Success -> null
            is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
        }

    /**
     * The versions of the expense [uuid], the one shown first and the earliest last, or why they
     * could not be read: a one-time expense's each by its creditor, see
     * [ExpenseRepository.getOneTimeExpenseHistory], a group expense's each by whichever member signed
     * it, see [ExpenseRepository.getGroupExpenseHistory].
     */
    suspend fun history(uuid: String): ExpenseHistoryOutcome {
        if (groupOf(uuid) != null) {
            return guarded("history", ExpenseHistoryOutcome.Unavailable(failure = ExpenseHistoryFailure.UNKNOWN)) {
                when (val history = expenseRepository.getGroupExpenseHistory(uuid = uuid)) {
                    is Result.Success -> ExpenseHistoryOutcome.Versions(
                        versions = history.data.map { version ->
                            ExpenseHistoryVersion(
                                revision = version.revision,
                                expense = version.expense,
                                authorUuid = version.authorUuid,
                                isCreation = version.isCreation,
                                madeAt = version.madeAt?.toString(),
                            )
                        },
                    )

                    is Result.Error -> ExpenseHistoryOutcome.Unavailable(
                        failure = history.error.toHistoryFailure(isOnline = networkConnectivity.isOnline()),
                    )
                }
            }
        }

        return when (val history = expenseRepository.getOneTimeExpenseHistory(uuid = uuid)) {
            is Result.Success -> ExpenseHistoryOutcome.Versions(
                versions = history.data.map { version ->
                    ExpenseHistoryVersion(
                        revision = version.revision,
                        expense = version.expense,
                        // Nobody but the creditor can sign a version, and every version here was
                        // checked to be signed by them.
                        authorUuid = version.expense.creditorUuid,
                        isCreation = version.isCreation,
                        madeAt = version.madeAt?.toString(),
                    )
                },
            )

            is Result.Error -> ExpenseHistoryOutcome.Unavailable(
                failure = history.error.toHistoryFailure(isOnline = networkConnectivity.isOnline()),
            )
        }
    }

    /** The group the stored expense [uuid] belongs to, which says which writer takes it, or null. */
    private suspend fun groupOf(uuid: String): String? =
        expenseRepository.getWithBeneficiaries(uuid = uuid).first()?.groupUuid

    /**
     * Runs [block], or tells the keychain's [IllegalStateException], thrown once the device locks,
     * as [fallback]: reaching Swift, it would end the app.
     */
    private inline fun <T> guarded(caller: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IllegalStateException) {
            println("ExpenseManagement: $caller: the device locked, or a check failed: $exception")
            fallback
        }

    /**
     * [this] as the form tells it. Whoever deleted their account is named as this device shows
     * them, hidden or not: a contact may have been forgotten for it already, which hides them and
     * keeps their name.
     */
    private suspend fun OneTimeExpenseWriteError.toSaveFailure(): ExpenseSaveFailure =
        toSaveFailure(
            isOnline = networkConnectivity.isOnline(),
            fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
        )
}

/** What reading the history of an expense gave. */
sealed interface ExpenseHistoryOutcome {

    /** Its versions, the one shown first and the earliest last. */
    data class Versions(val versions: List<ExpenseHistoryVersion>) : ExpenseHistoryOutcome

    /** Why there are none to show. */
    data class Unavailable(val failure: ExpenseHistoryFailure) : ExpenseHistoryOutcome
}

/** One version of an expense, as its history shows it. */
data class ExpenseHistoryVersion(
    /** Which version this is, which no other version of the expense shares. */
    val revision: Long,

    val expense: ExpenseWithBeneficiaries,

    /** Who made this version. */
    val authorUuid: String,

    /** Whether this is the expense as it was created, rather than as an edit left it. */
    val isCreation: Boolean,

    /**
     * When this version was made, as an ISO 8601 date and time with no time zone, or null when that
     * is not known, see [OneTimeExpenseVersion.madeAt].
     */
    val madeAt: String?,
)
