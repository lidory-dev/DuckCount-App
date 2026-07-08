package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import database.domain.repository.ExpenseRepository
import database.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toModel() }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override suspend fun deleteExpense(expenseUuid: String) {
        val expense = expenseRepository.getWithBeneficiaries(expenseUuid).first()
        if (expense?.groupUuid == null) {
            // One-time expense: online-first write-through (server delete, then local hard delete).
            // TODO: surface a NetworkError to the UI instead of silently dropping.
            expenseRepository.deleteOneTimeExpense(expenseUuid)
        } else {
            expenseRepository.delete(expenseUuid)
        }
    }

    override suspend fun restoreExpense(expenseUuid: String) {
        expenseRepository.restore(expenseUuid)
    }
}
