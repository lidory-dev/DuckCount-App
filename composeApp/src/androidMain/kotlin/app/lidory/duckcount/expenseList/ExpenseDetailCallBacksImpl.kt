package app.lidory.duckcount.expenseList

import android.util.Log
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.repository.ExpenseRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import util.Result

private const val TAG = "ExpenseDetailCallBacks"

class ExpenseDetailCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val networkConnectivity: NetworkConnectivity,
) : ExpenseDetailCallBacks {

    override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).map { it?.toExpenseListModel() }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getDisplayName(userUuid)
    }

    override fun isGroupLeft(groupUuid: String): Flow<Boolean> {
        return groupRepository.isLeft(uuid = groupUuid)
    }

    override suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError? {
        val expense = expenseRepository.getWithBeneficiaries(expenseUuid).first()

        val groupUuid = expense?.groupUuid

        // A version saying it is deleted, which a settlement's recipient alone writes.
        if (groupUuid != null) {
            return when (val result = expenseRepository.deleteGroupExpense(groupUuid = groupUuid, uuid = expenseUuid)) {
                is Result.Success -> null
                is Result.Error -> result.error.toExpenseWriteError(isOnline = networkConnectivity.isOnline())
            }
        }

        // One-time expense: online-first write-through (server delete, then local hard delete).
        return when (val result = expenseRepository.deleteOneTimeExpense(expenseUuid)) {
            is Result.Success -> null
            is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
        }
    }

    override suspend fun setSettled(expenseUuid: String, isSettled: Boolean): ExpenseWriteError? =
        when (val result = expenseRepository.setOneTimeExpenseSettled(uuid = expenseUuid, isSettled = isSettled)) {
            is Result.Success -> null
            is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
        }

    // Only a group expense is ever shown deleted, a one-time expense going with its deletion: one gone
    // since, with a log-out say, leaves nothing to restore, and the screen closes.
    override suspend fun restoreExpense(expenseUuid: String): ExpenseWriteError? {
        val groupUuid = expenseRepository.getWithBeneficiaries(expenseUuid).first()?.groupUuid ?: return null

        return when (val result = expenseRepository.restoreGroupExpense(groupUuid = groupUuid, uuid = expenseUuid)) {
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "restoreExpense: could not restore the expense: ${result.error}")

                result.error.toExpenseWriteError(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }
}
