package app.lidory.duckcount.expenseList

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
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
    ExpenseError.SHARES_BELOW_TOTAL_AMOUNT -> expenseListR.string.shares_below_total_amount
    ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT -> expenseListR.string.shares_exceed_total_amount
    ExpenseError.NON_POSITIVE_SHARE -> expenseListR.string.non_positive_amount_share
    ExpenseError.SHARES_EMPTY -> expenseListR.string.non_positive_amount_share
    ExpenseError.NO_BENEFICIARIES -> expenseListR.string.no_beneficiaries

    else -> null
}
