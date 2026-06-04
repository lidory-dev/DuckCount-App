package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import database.expense.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { expenseWithBeneficiaries ->
            expenseWithBeneficiaries?.let {
                ExpenseWithBeneficiaries(
                    uuid = it.uuid,
                    timestamp = it.timestamp,
                    title = it.title,
                    description = it.description,
                    amount = it.amount,
                    creditorUuid = it.creditorUuid,
                    isRefund = it.isRefund,
                    currency = Currency(
                        uuid = it.currency.uuid,
                        name = it.currency.name,
                        symbol = it.currency.symbol,
                        hasSubunits = it.currency.hasSubunits,
                    ),
                    beneficiaries = it.beneficiaries.map { beneficiary ->
                        Beneficiary(
                            uuid = beneficiary.uuid,
                            expenseUuid = beneficiary.expenseUuid,
                            userUuid = beneficiary.userUuid,
                            share = beneficiary.share,
                        )
                    },
                    groupUuid = it.groupUuid,
                )
            }
        }
    }

    override suspend fun deleteExpense(expenseUuid: String) {
        expenseRepository.deleteFromUuid(expenseUuid)
    }
}