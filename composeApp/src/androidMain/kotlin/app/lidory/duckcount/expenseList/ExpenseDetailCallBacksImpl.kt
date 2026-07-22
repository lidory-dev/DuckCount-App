package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import util.Result

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val networkConnectivity: NetworkConnectivity,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toModel() }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError? {
        val expense = expenseRepository.getWithBeneficiaries(expenseUuid).first()

        return expense?.groupUuid?.let {
            // TODO: "Delete" on the server.
            expenseRepository.delete(expenseUuid)
            null
        } ?: run {
            // One-time expense: online-first write-through (server delete, then local hard delete).
            when (val result = expenseRepository.deleteOneTimeExpense(expenseUuid)) {
                is Result.Success -> null
                is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
            }
        }
    }

    override suspend fun restoreExpense(expenseUuid: String) {
        expenseRepository.restore(expenseUuid)
    }
}
