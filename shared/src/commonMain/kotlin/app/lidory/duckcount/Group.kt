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
        usersUuid = members.map { it.userUuid },
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
