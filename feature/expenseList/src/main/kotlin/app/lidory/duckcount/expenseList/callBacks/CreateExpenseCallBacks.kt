package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
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

    /** Returns null when write succeeds (server + local), or the reason it failed. */
    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseWriteError?

    fun getUserFullName(userUuid: String): Flow<String?>

    fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries>

    /** Returns null when write succeeds (server + local), or the reason it failed. */
    suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseWriteError?

    fun getAuthenticatedUserUuid(): String?

    fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedUuid: Map<String, String>,
        expenseUuid: String,
    ): List<Beneficiary>

    fun getGroupCurrency(groupUuid: String): Flow<Currency>

    /** The currencies a one-time expense can be created with (single source of truth). */
    fun getCurrencies(): List<Currency>

    /** Currency selected by default for a new one-time expense. */
    fun getDefaultCurrency(): Currency

    /** Localized, human-readable name for an ISO 4217 currency [code] (e.g. "USD" -> "US Dollar"). */
    fun getCurrencyDisplayName(code: String): String
}
