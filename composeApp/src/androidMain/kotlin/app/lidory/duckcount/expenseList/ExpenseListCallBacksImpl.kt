package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import database.beneficiary.BeneficiaryRepository
import database.expense.ExpenseRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class ExpenseListCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : ExpenseListCallBacks {

    override fun getAllExpenses(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAll().map { expenses -> expenses.map { it.toModel() } }
    }

    override suspend fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseRepository.getWithBeneficiaries(uuid = uuid).filterNotNull().map { it.toModel() }
    }

    override suspend fun insert(expense: Expense) {
        expenseRepository.insert(expense.toDb())
    }

    override suspend fun getAllUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter).map { users -> users.map { it.toModel() } }
    }

    override suspend fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter, groupUuid)
            .map { users -> users.map { it.toModel() } }
    }

    override suspend fun insertBeneficiary(beneficiary: Beneficiary) {
        beneficiaryRepository.insert(beneficiary.toDb())
    }

    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? {
        return validateExpenseToMessageRes(amount, currency, beneficiaries)
    }

    override fun getAllExpensesWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllWithoutGroup().map { expenses -> expenses.map { it.toModel() } }
    }

    override fun getAllExpensesFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllFromGroup(groupUuid).map { expenses -> expenses.map { it.toModel() } }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }
}
