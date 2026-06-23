package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.flow.Flow

interface CreateExpenseCallBacks {

    val maxTitleLength: Int

    val maxDescriptionLength: Int

    fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int?

    fun getAllUsersFiltered(filter: String): Flow<List<User>>

    fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    suspend fun insert(expense: Expense)

    suspend fun insertBeneficiary(beneficiary: Beneficiary)

    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    fun getUserFullName(userUuid: String): Flow<String?>

    fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries>

    suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries)

    fun getAuthenticatedUserUuid(): String?

    fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedUuid: Map<String, String>,
        expenseUuid: String,
    ): List<Beneficiary>
}