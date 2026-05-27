package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import database.expense.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseListCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
) : ExpenseListCallBacks {

    override fun getAllExpenses(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAll().map { expensesWithBeneficiaries ->
            expensesWithBeneficiaries.map { expenseWithBeneficiaries ->
                ExpenseWithBeneficiaries(
                    uuid = expenseWithBeneficiaries.uuid,
                    timestamp = expenseWithBeneficiaries.timestamp,
                    title = expenseWithBeneficiaries.title,
                    description = expenseWithBeneficiaries.description,
                    amount = expenseWithBeneficiaries.amount,
                    creditorUuid = expenseWithBeneficiaries.creditorUuid,
                    isRefund = expenseWithBeneficiaries.isRefund,
                    currency = Currency(
                        uuid = expenseWithBeneficiaries.currency.uuid,
                        name = expenseWithBeneficiaries.currency.name,
                        symbol = expenseWithBeneficiaries.currency.symbol,
                        hasSubunits = expenseWithBeneficiaries.currency.hasSubunits,
                    ),
                    beneficiaries = expenseWithBeneficiaries.beneficiaries.map {
                        Beneficiary(
                            uuid = it.uuid,
                            expenseUuid = it.expenseUuid,
                            userUuid = it.userUuid,
                            share = it.share,
                        )
                    },
                )
            }
        }
    }
}