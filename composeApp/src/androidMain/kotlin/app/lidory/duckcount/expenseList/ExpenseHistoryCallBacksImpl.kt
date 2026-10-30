package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseHistory
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import util.Result

class ExpenseHistoryCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val networkConnectivity: NetworkConnectivity,
) : ExpenseHistoryCallBacks {

    override suspend fun getHistory(expenseUuid: String): ExpenseHistory {
        val isGroupExpense = expenseRepository.getWithBeneficiaries(expenseUuid).first()?.groupUuid != null

        if (isGroupExpense) {
            return when (val history = expenseRepository.getGroupExpenseHistory(uuid = expenseUuid)) {
                is Result.Success -> ExpenseHistory.Versions(versions = history.data.map { it.toExpenseListModel() })

                is Result.Error -> ExpenseHistory.Unavailable(
                    error = history.error.toExpenseHistoryError(isOnline = networkConnectivity.isOnline()),
                )
            }
        }

        return when (val history = expenseRepository.getOneTimeExpenseHistory(uuid = expenseUuid)) {
            is Result.Success -> ExpenseHistory.Versions(versions = history.data.map { it.toExpenseListModel() })

            is Result.Error -> ExpenseHistory.Unavailable(
                error = history.error.toExpenseHistoryError(isOnline = networkConnectivity.isOnline()),
            )
        }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getDisplayName(userUuid)
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }
}
