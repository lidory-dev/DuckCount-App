package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import java.math.RoundingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private const val AUTHENTICATED_USER = "11111111-1111-1111-1111-111111111111"

private val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)

/**
 * Works the shares out as the library does for what the form's tests look at, without its rounding
 * of subunits: fixed ones as they are, percentages of the whole amount, what is left by weight, one
 * part for who has none, with a weight stated for all of them once anybody has one. Nothing else
 * the form asks of these tests is more than a placeholder.
 */
internal open class ShareCalculatingCallBacks : CreateExpenseCallBacks {

    var lastFixedShares: Map<String, String> = emptyMap()
    var lastPercentages: Map<String, String> = emptyMap()
    var lastWeights: Map<String, String> = emptyMap()

    override val maxTitleLength = 50
    override val maxDescriptionLength = 500
    override val maxBeneficiaries = 100

    override fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedUuid: Map<String, String>,
        sharePercentages: Map<String, String>,
        shareWeights: Map<String, String>,
        expenseUuid: String,
    ): List<Beneficiary> {
        lastFixedShares = fixedUuid
        lastPercentages = sharePercentages
        lastWeights = shareWeights

        val total = amount.toBigDecimal()

        val set = fixedUuid.mapValues { it.value.toBigDecimal() } +
            sharePercentages.mapValues {
                (total * it.value.toBigDecimal()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
            }

        val left = set.values.fold(total) { rest, share -> rest - share }

        val weights = beneficiaryUserUuids
            .filterNot { it in set }
            .associateWith { (shareWeights[it] ?: "1").toBigDecimal() }

        val allWeights = weights.values.fold(0.toBigDecimal()) { sum, weight -> sum + weight }

        return beneficiaryUserUuids.map { userUuid ->
            val weight = weights[userUuid]

            Beneficiary(
                expenseUuid = expenseUuid,
                userUuid = userUuid,
                share = (set[userUuid] ?: (left * weight!!).divide(allWeights, 2, RoundingMode.HALF_UP)).toPlainString(),
                sharePercentage = sharePercentages[userUuid],
                shareWeight = if (weight == null || shareWeights.isEmpty()) null else shareWeights[userUuid] ?: "1",
            )
        }
    }

    override fun getCurrencies(): List<Currency> = listOf(EURO)
    override fun getDefaultCurrency(): Currency = EURO
    override fun getGroupCurrency(groupUuid: String): Flow<Currency> = flowOf(EURO)
    override fun getAuthenticatedUserUuid(): String? = AUTHENTICATED_USER
    override fun getUserFullName(userUuid: String): Flow<String?> = flowOf(null)
    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? = null
    override fun getCurrencyDisplayName(code: String): String = code
    override fun getAllUsersFiltered(filter: String): Flow<List<User>> = flowOf(emptyList())
    override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = flowOf(emptyList())
    override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> = error("Not read")
    override suspend fun insert(expense: Expense) = Unit
    override suspend fun insertBeneficiary(beneficiary: Beneficiary) = Unit
    override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? = null
    override suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? = null
}
