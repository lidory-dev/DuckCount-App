package app.lidory.duckcount.expenseList

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.DeletedBeneficiary
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseHistoryError
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseVersion
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.User
import util.NetworkError
import app.lidory.duckcount.expenseList.R as expenseListR
import app.lidory.duckcount.validateExpense as validateExpenseShared
import database.domain.Beneficiary as BeneficiaryDB
import database.domain.Currency as CurrencyDB
import database.domain.Expense as ExpenseDB
import database.domain.ExpenseWithBeneficiaries as ExpenseWithBeneficiariesDB
import database.domain.User as UserDB
import uniffi.lib_duckcount.Currency as CurrencyUniffi
import expense.OneTimeExpenseHistoryError
import expense.OneTimeExpenseVersion
import expense.OneTimeExpenseWriteError
import vault.VaultError

/**
 * Why a one-time expense write failed, as the UI explains it.
 *
 * Only [OneTimeExpenseWriteError.KeyMissing] earns wording of its own: it is a state the
 * user can wait out, and it says something true about their own device. A vault that could
 * not be reached is told apart like the server itself, and a missing root key like a session
 * that is gone. The other vault, identity and sealing failures are folded into `UNKNOWN` the
 * way the contact mappers fold theirs, since there is nothing a user can do differently about
 * any of them. Who deleted their account is told by name instead, see [toExpenseSaveError].
 */
fun OneTimeExpenseWriteError.toExpenseWriteError(isOnline: Boolean): ExpenseWriteError =
    when (this) {
        is OneTimeExpenseWriteError.Network -> error.toExpenseWriteError(isOnline = isOnline)

        OneTimeExpenseWriteError.KeyMissing -> ExpenseWriteError.KEY_MISSING

        OneTimeExpenseWriteError.NoSession -> ExpenseWriteError.UNAUTHORIZED

        is OneTimeExpenseWriteError.Vault -> when (val vaultError = error) {
            // Creating or editing reads the key ring from the server before the expense is
            // sent, so this is usually where being offline shows.
            is VaultError.Network -> vaultError.error.toExpenseWriteError(isOnline = isOnline)

            // Only ever unsealed during a registration or a login, so logging in again is
            // the only way back.
            VaultError.MissingRootKey -> ExpenseWriteError.UNAUTHORIZED

            VaultError.Contended,
            VaultError.Crypto,
            VaultError.MalformedKeyRing,
            VaultError.MalformedContactBook,
            VaultError.MalformedMemberProfileBook,
            VaultError.OrphanedEntry,
                -> ExpenseWriteError.UNKNOWN
        }

        // Told apart by toExpenseSaveError, for the form, the one place it comes up: nothing but
        // creating or editing an expense names anybody.
        is OneTimeExpenseWriteError.BeneficiariesDeleted -> ExpenseWriteError.INVALID_DATA

        is OneTimeExpenseWriteError.Identity,
        OneTimeExpenseWriteError.Crypto,
            -> ExpenseWriteError.UNKNOWN
    }

/**
 * Why saving the expense form failed, as the form explains it: who deleted their account, by the
 * name [fullNameOf] reads for each of them, or else as [toExpenseWriteError] tells it.
 */
suspend fun OneTimeExpenseWriteError.toExpenseSaveError(
    isOnline: Boolean,
    fullNameOf: suspend (userUuid: String) -> String?,
): ExpenseSaveError =
    when (this) {
        is OneTimeExpenseWriteError.BeneficiariesDeleted -> ExpenseSaveError.BeneficiariesDeleted(
            beneficiaries = userUuids.map { DeletedBeneficiary(userUuid = it, fullName = fullNameOf(it)) },
        )

        else -> ExpenseSaveError.Write(error = toExpenseWriteError(isOnline = isOnline))
    }

/**
 * Maps the low-level networking error to the reason the UI surfaces for a failed write.
 *
 * The "couldn't connect" case ([NetworkError.UNREACHABLE]) is resolved against real device
 * connectivity ([isOnline]): airplane mode can surface as UNREACHABLE rather than NO_INTERNET
 * and must be corrected here.
 */
fun NetworkError.toExpenseWriteError(isOnline: Boolean): ExpenseWriteError = when (this) {
    NetworkError.NO_INTERNET -> ExpenseWriteError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ExpenseWriteError.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) ExpenseWriteError.SERVER_UNREACHABLE else ExpenseWriteError.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNPROCESSABLE_ENTITY,
        -> ExpenseWriteError.INVALID_DATA

    NetworkError.CONFLICT -> ExpenseWriteError.CONFLICT

    NetworkError.UNAUTHORIZED -> ExpenseWriteError.UNAUTHORIZED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ExpenseWriteError.UNKNOWN
}

/**
 * Why the history of a one-time expense could not be read, as the UI explains it. A device
 * that cannot open the expense is told apart from nothing else, as there is nothing the user
 * can do about it.
 */
fun OneTimeExpenseHistoryError.toExpenseHistoryError(isOnline: Boolean): ExpenseHistoryError =
    when (this) {
        is OneTimeExpenseHistoryError.Network -> when (error) {
            NetworkError.NO_INTERNET -> ExpenseHistoryError.NO_INTERNET
            NetworkError.REQUEST_TIMEOUT -> ExpenseHistoryError.TIMEOUT

            NetworkError.UNREACHABLE ->
                if (isOnline) ExpenseHistoryError.SERVER_UNREACHABLE else ExpenseHistoryError.NO_INTERNET

            else -> ExpenseHistoryError.UNKNOWN
        }

        OneTimeExpenseHistoryError.Unreadable -> ExpenseHistoryError.UNKNOWN
    }

fun OneTimeExpenseVersion.toExpenseListModel(): ExpenseVersion = ExpenseVersion(
    revision = revision,
    expense = expense.toExpenseListModel(),
    // Nobody but the creditor can sign a version, and every version here was checked to be
    // signed by them: whatever the server says about who made it is not asked.
    authorUuid = expense.creditorUuid,
    isCreation = isCreation,
    madeAt = madeAt,
)

// Mapping between the :shared database models (database.*) and the feature-module
// models (expenseList.model.*). composeApp is the only layer that sees both, so the
// conversions live here rather than in :shared or the feature module.

fun CurrencyDB.toExpenseListModel(): Currency = Currency(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)

fun Currency.toDb(): CurrencyDB = CurrencyDB(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)

fun Currency.toUniffi(): CurrencyUniffi = CurrencyUniffi(
    name = name,
    symbol = symbol,
    hasSubUnit = hasSubunits,
)

fun BeneficiaryDB.toExpenseListModel(): Beneficiary = Beneficiary(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)

fun Beneficiary.toDb(): BeneficiaryDB = BeneficiaryDB(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)

fun UserDB.toExpenseListModel(): User = User(
    uuid = uuid,
    username = username,
    fullName = fullName,
)

fun Expense.toDb(): ExpenseDB = ExpenseDB(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = currency.toDb(),
    groupUuid = groupUuid,
)

fun ExpenseWithBeneficiariesDB.toExpenseListModel(): ExpenseWithBeneficiaries = ExpenseWithBeneficiaries(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = currency.toExpenseListModel(),
    beneficiaries = beneficiaries.map { it.toExpenseListModel() },
    groupUuid = groupUuid,
    isDeleted = isDeleted,
    isSettled = isSettled,
    settledAt = settledAt,
)

fun ExpenseWithBeneficiaries.toDb(): ExpenseWithBeneficiariesDB = ExpenseWithBeneficiariesDB(
    uuid = uuid,
    title = title,
    timestamp = timestamp,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = currency.toDb(),
    beneficiaries = beneficiaries.map { it.toDb() },
    groupUuid = groupUuid,
)

/** Runs shared expense validation and maps the resulting error to a string resource id, or null. */
fun validateExpenseToMessageRes(
    amount: String,
    currency: Currency,
    beneficiaries: List<Beneficiary>,
): Int? = validateExpenseShared(
    amount,
    currency.toDb(),
    beneficiaries.map { it.toDb() },
).toMessageResOrNull()

private fun ExpenseError?.toMessageResOrNull(): Int? = when (this) {
    null -> null

    ExpenseError.SHARES_BELOW_TOTAL_AMOUNT -> expenseListR.string.shares_below_total_amount
    ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT -> expenseListR.string.shares_exceed_total_amount
    ExpenseError.NON_POSITIVE_SHARE -> expenseListR.string.non_positive_amount_share
    ExpenseError.SHARES_EMPTY -> expenseListR.string.non_positive_amount_share
    ExpenseError.NO_BENEFICIARIES -> expenseListR.string.no_beneficiaries

    // The beneficiary list's header shows the limit next to the count, so the message need not.
    ExpenseError.TOO_MANY_BENEFICIARIES -> expenseListR.string.too_many_beneficiaries

    // What a typed amount or share can still be: zero, or too long. Never let through:
    // everybody an expense names refuses one that does not validate, and would never see it.
    ExpenseError.OTHER_KNOWN_ERROR -> expenseListR.string.invalid_amount
}
