package app.lidory.duckcount.expenseList

import app.lidory.duckcount.ExpenseLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.ExpenseLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.computeBeneficiaries
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.repository.BeneficiaryRepository
import database.data.repository.ExpenseRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import database.domain.CurrencyCatalog
import database.domain.currencyDisplayName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import util.Result

class CreateExpenseCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
    private val networkConnectivity: NetworkConnectivity,
    private val groupRepository: GroupRepository,
) : CreateExpenseCallBacks {

    override val maxTitleLength: Int = MAX_TITLE_LENGTH

    override val maxDescriptionLength: Int = MAX_DESCRIPTION_LENGTH

    override suspend fun insert(expense: Expense) {
        expenseRepository.insert(expense.toDb())
    }

    override fun getAllUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter).map { users -> users.map { it.toExpenseListModel() } }
    }

    override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter = filter, groupUuid = groupUuid)
            .map { users -> users.map { it.toExpenseListModel() } }
    }

    override suspend fun insertBeneficiary(beneficiary: Beneficiary) {
        beneficiaryRepository.insert(beneficiary.toDb())
    }

    override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseWriteError? {
        // Group expense: local-only write. Returning early is essential, the one-time
        // branch below must not also run (it would POST the expense to the server and
        // insert a second local copy).
        if (expenseWithBeneficiaries.groupUuid != null) {
            expenseRepository.insertWithBeneficiaries(expenseWithBeneficiaries.toDb())
            return null
        }

        // One-time expense: online-first write-through.
        return when (val result = expenseRepository.createOneTimeExpense(expenseWithBeneficiaries.toDb())) {
            is Result.Success -> null
            is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
        }
    }

    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? {
        return validateExpenseToMessageRes(amount, currency, beneficiaries)
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseRepository.getWithBeneficiaries(expenseUuid).filterNotNull().map { it.toExpenseListModel() }
    }

    override suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseWriteError? {
        // Group expense: local-only write. Returning early is essential, the one-time
        // branch below must not also run (it would PUT the expense to the server).
        if (expenseWithBeneficiaries.groupUuid != null) {
            expenseRepository.updateWithBeneficiaries(expenseWithBeneficiaries.toDb())
            return null
        }

        // One-time expense: online-first write-through.
        return when (val result = expenseRepository.updateOneTimeExpense(expenseWithBeneficiaries.toDb())) {
            is Result.Success -> null
            is Result.Error -> result.error.toExpenseWriteError(networkConnectivity.isOnline())
        }
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedUuid: Map<String, String>,
        expenseUuid: String,
    ): List<Beneficiary> {
        return computeBeneficiaries(
            amount = amount,
            currency = currency.toUniffi(),
            beneficiaryUserUuids = beneficiaryUserUuids,
            fixedShares = fixedUuid,
        ).map {
            Beneficiary(
                expenseUuid = expenseUuid,
                userUuid = it.userUuid,
                share = it.share,
                isShareFixed = false,
            )
        }
    }

    override fun getGroupCurrency(groupUuid: String): Flow<Currency> {
        return groupRepository.get(groupUuid).map { it.currency.toExpenseListModel() }
    }

    override fun getCurrencies(): List<Currency> =
        CurrencyCatalog.defaultCurrencies.map { it.toExpenseListModel() }

    override fun getDefaultCurrency(): Currency = CurrencyCatalog.defaultCurrency.toExpenseListModel()

    override fun getCurrencyDisplayName(code: String): String = currencyDisplayName(code)
}
