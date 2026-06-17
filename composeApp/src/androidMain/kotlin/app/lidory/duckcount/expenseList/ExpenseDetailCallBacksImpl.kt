package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import database.expense.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toModel() }
    }

    override suspend fun deleteExpense(expenseUuid: String) {
        expenseRepository.deleteFromUuid(expenseUuid)
    }

    override suspend fun enableExpense(expenseUuid: String) {
        expenseRepository.enableFromUuid(expenseUuid)
    }
}
