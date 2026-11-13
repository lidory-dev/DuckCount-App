package expense

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.ExpenseLimits
import app.lidory.duckcount.validateExpense
import authentication.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSync
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import database.domain.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import util.Result
import app.lidory.duckcount.computeBeneficiaries as computeShares
import uniffi.lib_duckcount.Currency as LibraryCurrency

/**
 * What the iOS expense screens run on the user's one-time expenses, with the same repositories and
 * in the same order as Android's `ExpenseListCallBacksImpl`, `ExpenseDetailCallBacksImpl`,
 * `CreateExpenseCallBacksImpl` and `ExpenseHistoryCallBacksImpl`, which live in the Android app,
 * and with their failures told as those tell them, see [ExpenseWriteFailure].
 */
class OneTimeExpenses(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val oneTimeExpenseSync: OneTimeExpenseSync,
    private val contactSync: ContactSync,
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

    fun authenticatedUserUuid(): String? = getAuthenticatedUserUuid()

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
     * Brings the one-time expenses up to date with the server, and says whether their listing was
     * read.
     *
     * What Android's list does as it opens, see `ExpenseRemoteMediator`, after what a push makes
     * its device do, see `PushSyncWorker`, iOS receiving none yet: the contact sync brings the
     * contacts back after a log-in and reads the messages, the keys to the expenses others shared
     * among them, so that the listing read next opens them. Its failure is not this one, whatever
     * it did not take in being picked up by the next refresh, and neither is handing over the keys
     * the user owes or asking for those missing, which follow the listing.
     */
    suspend fun refresh(): Boolean {
        contactSync.sync()

        if (oneTimeExpenseSync.refresh() is Result.Error) return false

        oneTimeExpenseSync.shareOwedKeys()
        oneTimeExpenseSync.requestMissingKeys()

        return true
    }

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
     * Creates [expense] on the server first, see [ExpenseRepository.createOneTimeExpense]: null once
     * it is written, or why it is not.
     */
    suspend fun create(expense: ExpenseWithBeneficiaries): ExpenseSaveFailure? =
        when (val result = expenseRepository.createOneTimeExpense(expenseWithBeneficiaries = expense)) {
            is Result.Success -> null
            is Result.Error -> result.error.toSaveFailure()
        }

    /** Writes [expense] over the one it edits, see [ExpenseRepository.updateOneTimeExpense]. */
    suspend fun update(expense: ExpenseWithBeneficiaries): ExpenseSaveFailure? =
        when (val result = expenseRepository.updateOneTimeExpense(expenseWithBeneficiaries = expense)) {
            is Result.Success -> null
            is Result.Error -> result.error.toSaveFailure()
        }

    /** Deletes the expense [uuid], on the server first, see [ExpenseRepository.deleteOneTimeExpense]. */
    suspend fun delete(uuid: String): ExpenseWriteFailure? =
        when (val result = expenseRepository.deleteOneTimeExpense(uuid = uuid)) {
            is Result.Success -> null
            is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
        }

    /** Marks the user's expense [uuid] as paid back, or no longer, see [ExpenseRepository.setOneTimeExpenseSettled]. */
    suspend fun setSettled(uuid: String, isSettled: Boolean): ExpenseWriteFailure? =
        when (val result = expenseRepository.setOneTimeExpenseSettled(uuid = uuid, isSettled = isSettled)) {
            is Result.Success -> null
            is Result.Error -> result.error.toWriteFailure(isOnline = networkConnectivity.isOnline())
        }

    /**
     * The versions of the expense [uuid], the one shown first and the earliest last, each by its
     * creditor, see [ExpenseRepository.getOneTimeExpenseHistory], or why they could not be read.
     */
    suspend fun history(uuid: String): ExpenseHistoryOutcome =
        when (val history = expenseRepository.getOneTimeExpenseHistory(uuid = uuid)) {
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
