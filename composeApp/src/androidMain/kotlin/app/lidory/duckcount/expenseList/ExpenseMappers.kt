package app.lidory.duckcount.expenseList

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.expenseList.R as expenseListR
import app.lidory.duckcount.validateExpense as validateExpenseShared
import database.beneficiary.Beneficiary as BeneficiaryDB
import database.currency.Currency as CurrencyDB
import database.expense.Expense as ExpenseDB
import database.expense.ExpenseWithBeneficiaries as ExpenseWithBeneficiariesDB
import database.user.User as UserDB

// Mapping between the :shared database models (database.*) and the feature-module
// models (expenseList.model.*). composeApp is the only layer that sees both, so the
// conversions live here rather than in :shared or the feature module.

fun CurrencyDB.toModel(): Currency = Currency(
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

fun BeneficiaryDB.toModel(): Beneficiary = Beneficiary(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
)

fun Beneficiary.toDb(): BeneficiaryDB = BeneficiaryDB(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
)

fun UserDB.toModel(): User = User(
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
    isRefund = isRefund,
    currency = currency.toDb(),
    groupUuid = groupUuid,
)

fun ExpenseWithBeneficiariesDB.toModel(): ExpenseWithBeneficiaries = ExpenseWithBeneficiaries(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isRefund = isRefund,
    currency = currency.toModel(),
    beneficiaries = beneficiaries.map { it.toModel() },
    groupUuid = groupUuid,
    isActive = isActive,
)

fun ExpenseWithBeneficiaries.toDb(): ExpenseWithBeneficiariesDB = ExpenseWithBeneficiariesDB(
    uuid = uuid,
    title = title,
    timestamp = timestamp,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isRefund = isRefund,
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
