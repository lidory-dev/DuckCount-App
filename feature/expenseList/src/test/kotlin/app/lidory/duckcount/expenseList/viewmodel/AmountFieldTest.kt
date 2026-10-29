package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The amount fields of the form: each holds its text as typed, which [CreateExpenseViewModel] never
 * rewrites, beside the amount it stands for, which the form works with and saves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmountFieldTest {

    // The ViewModel starts reading the form's data on the main dispatcher as it is built, which a
    // JVM test has none of. This one is never run, so nothing it launches happens during a test.
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Built in each test, once the main dispatcher is set.
    private val viewModel by lazy { CreateExpenseViewModel(createExpenseCallBacks = SplittingCallBacks()) }

    @Test
    fun `an amount typed with a comma is held as typed and stands for its amount`() {
        viewModel.updateAmount("12,5")

        assertEquals("12,5", viewModel.state.amountText)
        assertEquals("12.50", viewModel.state.amount)
    }

    @Test
    fun `leaving the amount field writes it as the amount it stands for`() {
        viewModel.updateAmount("12,5")
        viewModel.finishAmount()

        assertEquals("12.50", viewModel.state.amountText)
    }

    @Test
    fun `deleting the decimal separator drops the decimals`() {
        viewModel.updateAmount("12.50")
        viewModel.updateAmount("1250")

        assertEquals("12", viewModel.state.amountText)
        assertEquals("12", viewModel.state.amount)
    }

    @Test
    fun `a separator is refused in a currency without subunits`() {
        viewModel.updateCurrency(YEN)
        viewModel.updateAmount("12")
        viewModel.updateAmount("12.")

        assertEquals("12", viewModel.state.amountText)
    }

    /**
     * Working the shares out again writes the share being typed as the form keeps it, which the
     * field does not show until it is left: that would rewrite what is being typed.
     */
    @Test
    fun `a share is shown as typed until its field is left`() {
        viewModel.updateAmount("20")
        viewModel.updateBeneficiaries(
            listOf(
                Beneficiary(expenseUuid = "", userUuid = ADA, share = "10.00"),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = "10.00"),
            ),
        )

        viewModel.typeBeneficiaryShare(userUuid = ADA, text = "5,5")
        viewModel.setBeneficiaryFixedShare(userUuid = ADA)
        viewModel.calculateShares()

        assertEquals("5,5", viewModel.displayedShareOf(ADA))
        assertEquals("5.50", viewModel.state.beneficiaries.single { it.userUuid == ADA }.share)
        assertEquals("14.50", viewModel.displayedShareOf(BOB))

        viewModel.finishBeneficiaryShare(userUuid = ADA)

        assertEquals("5.50", viewModel.displayedShareOf(ADA))
    }

    private fun CreateExpenseViewModel.displayedShareOf(userUuid: String): String =
        displayedBeneficiaries(names = emptyMap()).single { it.userUuid == userUuid }.share

    private companion object {
        const val ADA = "ada"
        const val BOB = "bob"
        val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)
        val YEN = Currency(name = "Yen", symbol = "¥", hasSubunits = false)
    }

    /**
     * Answers what typing amounts asks: the shares, every fixed one as given and the rest of the
     * amount split evenly, every expense passing the rules.
     */
    private class SplittingCallBacks : CreateExpenseCallBacks {
        override val maxTitleLength = 50
        override val maxDescriptionLength = 2000
        override val maxBeneficiaries = 100

        override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? = null

        override fun computeBeneficiaries(
            amount: String,
            currency: Currency,
            beneficiaryUserUuids: List<String>,
            fixedUuid: Map<String, String>,
            expenseUuid: String,
        ): List<Beneficiary> {
            val fixed = fixedUuid.values.fold(BigDecimal.ZERO) { sum, share -> sum + BigDecimal(share) }
            val others = beneficiaryUserUuids.filterNot { it in fixedUuid }
            val each = if (others.isEmpty()) {
                BigDecimal.ZERO
            } else {
                (BigDecimal(amount) - fixed).divide(BigDecimal(others.size), 2, RoundingMode.DOWN)
            }

            return beneficiaryUserUuids.map { userUuid ->
                Beneficiary(expenseUuid = expenseUuid, userUuid = userUuid, share = fixedUuid[userUuid] ?: each.toPlainString())
            }
        }

        override fun getCurrencies(): List<Currency> = listOf(EURO, YEN)
        override fun getDefaultCurrency(): Currency = EURO
        override fun getCurrencyDisplayName(code: String): String = code

        override fun getAllUsersFiltered(filter: String): Flow<List<User>> = notWhileTyping()
        override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = notWhileTyping()
        override suspend fun insert(expense: Expense) = notWhileTyping()
        override suspend fun insertBeneficiary(beneficiary: Beneficiary) = notWhileTyping()
        override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? =
            notWhileTyping()
        override fun getUserFullName(userUuid: String): Flow<String?> = notWhileTyping()
        override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> = notWhileTyping()
        override suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? =
            notWhileTyping()
        // The rows list the user first, see CreateExpenseViewModel.displayedBeneficiaries.
        override fun getAuthenticatedUserUuid(): String? = "self"
        override fun getGroupCurrency(groupUuid: String): Flow<Currency> = notWhileTyping()

        private fun notWhileTyping(): Nothing = error("Not asked while amounts are typed")
    }
}
