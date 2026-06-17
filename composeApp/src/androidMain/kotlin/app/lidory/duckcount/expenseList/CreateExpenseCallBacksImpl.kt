package app.lidory.duckcount.expenseList

import app.lidory.duckcount.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.MAX_TITLE_LENGTH
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
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

class CreateExpenseCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : CreateExpenseCallBacks {

    override val maxTitleLength: Int = MAX_TITLE_LENGTH

    override val maxDescriptionLength: Int = MAX_DESCRIPTION_LENGTH

    override suspend fun insert(expense: Expense) {
        expenseRepository.insert(expense.toDb())
    }

    override fun getAllUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter).map { users -> users.map { it.toModel() } }
    }

    override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter = filter, groupUuid = groupUuid)
            .map { users -> users.map { it.toModel() } }
    }

    override suspend fun insertBeneficiary(beneficiary: Beneficiary) {
        beneficiaryRepository.insert(beneficiary.toDb())
    }

    override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseRepository.insertWithBeneficiaries(expenseWithBeneficiaries.toDb())
    }

    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? {
        return validateExpenseToMessageRes(amount, currency, beneficiaries)
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseRepository.getWithBeneficiaries(expenseUuid).filterNotNull().map { it.toModel() }
    }

    override suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseRepository.updateWithBeneficiaries(expenseWithBeneficiaries.toDb())
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }
}
