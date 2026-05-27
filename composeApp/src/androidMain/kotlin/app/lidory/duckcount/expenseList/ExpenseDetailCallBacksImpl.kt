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

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { expenseWithBeneficiaries ->
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
                beneficiaries = expenseWithBeneficiaries.beneficiaries.map { beneficiary ->
                    Beneficiary(
                        uuid = beneficiary.uuid,
                        expenseUuid = beneficiary.expenseUuid,
                        userUuid = beneficiary.userUuid,
                        share = beneficiary.share,
                    )
                },
            )
        }
    }
}