package app.lidory.duckcount

import database.domain.Currency
import database.domain.ExpenseWithBeneficiaries
import database.domain.GroupMember
import uniffi.lib_duckcount.Transaction
import uniffi.lib_duckcount.Beneficiary as uniffiBeneficiary
import uniffi.lib_duckcount.Currency as uniffiCurrency
import uniffi.lib_duckcount.Expense as uniffiExpense
import uniffi.lib_duckcount.Group as uniffiGroup


fun createGroup(
    currency: Currency,
    members: List<GroupMember>,
    expenses: List<ExpenseWithBeneficiaries>,
): uniffiGroup {
    return uniffiGroup(
        currency = uniffiCurrency(
            name = currency.name,
            symbol = currency.symbol,
            hasSubUnit = currency.hasSubunits,
        ),
        // Everybody the expenses name as well, whom the library refuses any expense naming
        // somebody else for: a member the creator removed is in the group no more, but the
        // expenses naming them stay, and so does what they owe or are owed.
        usersUuid = (members.map { it.userUuid } + expenses.flatMap { it.userUuids() }).distinct(),
        expenses = expenses.map { expense ->
            uniffiExpense(
                creditorUuid = expense.creditorUuid,
                amount = expense.amount,
                currency = uniffiCurrency(
                    name = expense.currency.name,
                    symbol = expense.currency.symbol,
                    hasSubUnit = expense.currency.hasSubunits,
                ),
                beneficiaries = expense.beneficiaries.map {
                    uniffiBeneficiary(
                        userUuid = it.userUuid,
                        share = it.share,
                    )
                },
            )
        },
    )
}

/** Everybody this expense names: who paid it, and who it was paid for. */
private fun ExpenseWithBeneficiaries.userUuids() = listOf(creditorUuid) + beneficiaries.map { it.userUuid }

fun calculateNetDebtsPerUser(
    currency: Currency,
    members: List<GroupMember>,
    expenses: List<ExpenseWithBeneficiaries>,
): Map<String, String> {
    return createGroup(currency, members, expenses).calculateNetDebtsPerUser()
}

fun getTransactionForGroup(
    currency: Currency,
    members: List<GroupMember>,
    expenses: List<ExpenseWithBeneficiaries>,
): List<Transaction> {
    return createGroup(currency, members, expenses).calculateTransactionsToEquilibrate()
}
