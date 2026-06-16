package app.lidory.duckcount

import database.expense.ExpenseWithBeneficiaries
import database.groupMember.GroupMember
import uniffi.lib_duckcount.Beneficiary as uniffiBeneficiary
import uniffi.lib_duckcount.Currency as uniffiCurrency
import uniffi.lib_duckcount.Expense as uniffiExpense
import uniffi.lib_duckcount.Group as uniffiGroup

fun calculateNetDebtsPerUser(
    members: List<GroupMember>,
    expenses: List<ExpenseWithBeneficiaries>,
): Map<String, String> {
    val group = uniffiGroup(
        currency = uniffiCurrency(
            // TODO: Use currency of the group
            name = "EUR", symbol = "€", hasSubUnit = true,
        ),
        usersId = members.map { it.userUuid },
        expenses = expenses.map { expense ->
            uniffiExpense(
                creditorId = expense.creditorUuid,
                amount = expense.amount,
                currency = uniffiCurrency(
                    name = expense.currency.name,
                    symbol = expense.currency.symbol,
                    hasSubUnit = expense.currency.hasSubunits,
                ),
                beneficiaries = expense.beneficiaries.map {
                    uniffiBeneficiary(
                        userId = it.userUuid,
                        share = it.share,
                    )
                },
            )
        },
    )

    return group.calculateNetDebtsPerUser()
}
