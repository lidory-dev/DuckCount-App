package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import database.expense.ExpenseRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toModel() }
    }

    override fun getUserFullName(userUuid: String): Flow<String> {
        return userRepository.getFullName(userUuid)
    }
}
    override suspend fun deleteExpense(expenseUuid: String) {
        expenseRepository.delete(expenseUuid)
    }

    override suspend fun restoreExpense(expenseUuid: String) {
        expenseRepository.restore(expenseUuid)
    }
}
