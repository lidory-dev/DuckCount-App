package app.lidory.duckcount

import database.domain.ExpenseWithBeneficiaries
import database.domain.GroupMember
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

    return group.calculateNetDebtsPerUser()
}
