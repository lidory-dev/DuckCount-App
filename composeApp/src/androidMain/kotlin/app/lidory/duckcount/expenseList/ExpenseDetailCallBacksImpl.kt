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
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toExpenseListModel() }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError? {
        val expense = expenseRepository.getWithBeneficiaries(expenseUuid).first()

        // Group expense: local-only delete. Returning early is essential, the one-time
        // branch below must not also run (it would issue a server delete for an expense
        // the server never received).
        if (expense?.groupUuid != null) {
            if (expense.isSettlement) {
                // Records a settlement between two people; only its beneficiary
                // (the person who received the money back) may remove it. Deleting it is a
                // soft delete (a settlement is never hard-deleted), but unlike a regular
                // expense it cannot be restored afterward.
                val authenticatedUserUuid = getAuthenticatedUserUuid()
                if (expense.beneficiaries.none { it.userUuid == authenticatedUserUuid }) {
                    return ExpenseWriteError.UNAUTHORIZED
                }
                expenseRepository.delete(expenseUuid)
                return null
            }

            // TODO: "Delete" on the server.
            expenseRepository.delete(expenseUuid)
            return null
        }

        // One-time expense: online-first write-through (server delete, then local hard delete).
        return when (val result = expenseRepository.deleteOneTimeExpense(expenseUuid)) {
            is Result.Success -> null
            is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
        }
    }

    override suspend fun restoreExpense(expenseUuid: String) {
        expenseRepository.restore(expenseUuid)
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }
}
