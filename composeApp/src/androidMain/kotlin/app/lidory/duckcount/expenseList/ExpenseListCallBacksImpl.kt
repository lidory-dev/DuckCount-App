package app.lidory.duckcount.expenseList

import androidx.paging.PagingData
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import androidx.paging.map as pagingMap
import app.lidory.duckcount.push.ExpenseNotifier

class ExpenseListCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val expenseNotifier: ExpenseNotifier,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : ExpenseListCallBacks {

    override fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>> {
        return expenseRepository.getOneTimeExpensesPaged()
            .map { pagingData -> pagingData.pagingMap { it.toExpenseListModel() } }
    }

    override suspend fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).filterNotNull().map { it.toExpenseListModel() }
    }

    override suspend fun insert(expense: Expense) {
        expenseRepository.insert(expense.toDb())
    }

    override suspend fun getAllUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter).map { users -> users.map { it.toExpenseListModel() } }
    }

    override suspend fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter, groupUuid)
            .map { users -> users.map { it.toExpenseListModel() } }
    }

    override suspend fun insertBeneficiary(beneficiary: Beneficiary) {
        beneficiaryRepository.insert(beneficiary.toDb())
    }

    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? {
        return validateExpenseToMessageRes(amount, currency, beneficiaries)
    }

    override fun getAllExpensesWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllWithoutGroup().map { expenses -> expenses.map { it.toExpenseListModel() } }
    }

    override fun getAllExpensesFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllFromGroup(groupUuid).map { expenses -> expenses.map { it.toExpenseListModel() } }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override fun newestExpensesShown() = expenseNotifier.newestShown()

    override fun newestExpensesHidden() = expenseNotifier.newestHidden()
}
