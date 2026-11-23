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
import java.util.Locale
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

    // Built in each test, once the main dispatcher is set, in English, whatever the machine runs:
    // the fields show their amounts with its decimal separator.
    private val viewModel by lazy { formIn(Locale.UK) }

    private fun formIn(locale: Locale) = CreateExpenseViewModel(createExpenseCallBacks = SplittingCallBacks(), locale = locale)

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
        assertEquals("12.00", viewModel.state.amount)
    }

    /** It is what is saved and compared, which a text of whole units would differ from. */
    @Test
    fun `an amount of whole units is held with its subunits and shown so once the field is left`() {
        viewModel.updateAmount("12")

        assertEquals("12", viewModel.state.amountText)
        assertEquals("12.00", viewModel.state.amount)

        viewModel.finishAmount()

        assertEquals("12.00", viewModel.state.amountText)
    }

    @Test
    fun `an amount is written anew for the currency it changes to`() {
        viewModel.updateAmount("12")
        viewModel.updateCurrency(YEN)

        assertEquals("12", viewModel.state.amount)

        viewModel.updateCurrency(EURO)

        assertEquals("12.00", viewModel.state.amount)
    }

    /** Rounded one by one, two shares of 60.25 would make 120, short of the 121 the amount rounds to. */
    @Test
    fun `picking a currency works the shares out again from the amount it rounds to`() {
        viewModel.updateAmount("120.5")
        viewModel.updateBeneficiaries(
            listOf(
                Beneficiary(expenseUuid = "", userUuid = ADA, share = "60.25"),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = "60.25"),
            ),
        )

        viewModel.selectCurrency(YEN)

        assertEquals("121", viewModel.state.amount)
        assertEquals(listOf("61", "60"), viewModel.state.beneficiaries.map { it.share })
    }

    @Test
    fun `picking a currency rounds a fixed share and the others share what it leaves`() {
        viewModel.updateAmount("120.5")
        viewModel.updateBeneficiaries(
            listOf(
                Beneficiary(expenseUuid = "", userUuid = ADA, share = "20.25"),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = "100.25"),
            ),
        )
        viewModel.setBeneficiaryFixedShare(userUuid = ADA)

        viewModel.selectCurrency(YEN)

        assertEquals(listOf("20", "101"), viewModel.state.beneficiaries.map { it.share })
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

    /** As the user's language writes a decimal, the amount the form keeps being the library's. */
    @Test
    fun `leaving the amount field writes it with the decimal separator of the language`() {
        val viewModel = formIn(Locale.FRANCE)

        viewModel.updateAmount("12.5")
        viewModel.finishAmount()

        assertEquals("12,50", viewModel.state.amountText)
        assertEquals("12.50", viewModel.state.amount)
    }

    @Test
    fun `an amount field written with a comma is edited as typed`() {
        val viewModel = formIn(Locale.FRANCE)
        viewModel.updateAmount("12.5")
        viewModel.finishAmount()

        viewModel.updateAmount("12,5")

        assertEquals("12,5", viewModel.state.amountText)
        assertEquals("12.50", viewModel.state.amount)

        viewModel.updateAmount("12,59")
        viewModel.finishAmount()
        viewModel.updateAmount("1259")

        assertEquals("12", viewModel.state.amountText)
        assertEquals("12.00", viewModel.state.amount)
    }

    @Test
    fun `a currency picked writes the amount field with the decimal separator of the language`() {
        val viewModel = formIn(Locale.FRANCE)
        viewModel.updateAmount("12")

        viewModel.updateCurrency(EURO)

        assertEquals("12,00", viewModel.state.amountText)
    }

    @Test
    fun `the shares are shown with the decimal separator of the language and kept as the library writes them`() {
        val viewModel = formIn(Locale.FRANCE)
        viewModel.updateAmount("20")
        viewModel.updateBeneficiaries(
            listOf(
                Beneficiary(expenseUuid = "", userUuid = ADA, share = "10.00"),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = "10.00"),
            ),
        )

        assertEquals("10,00", viewModel.displayedShareOf(ADA))

        // Its last decimal deleted, as the field shows it.
        viewModel.typeBeneficiaryShare(userUuid = ADA, text = "10,0")
        viewModel.setBeneficiaryFixedShare(userUuid = ADA)
        viewModel.calculateShares()

        assertEquals("10,0", viewModel.displayedShareOf(ADA))
        assertEquals("10.00", viewModel.state.beneficiaries.single { it.userUuid == ADA }.share)

        // Its comma deleted: whole units, as with a dot.
        viewModel.typeBeneficiaryShare(userUuid = ADA, text = "100")

        assertEquals("10", viewModel.displayedShareOf(ADA))
        assertEquals("10.00", viewModel.state.beneficiaries.single { it.userUuid == ADA }.share)

        viewModel.finishBeneficiaryShare(userUuid = ADA)

        assertEquals("10,00", viewModel.displayedShareOf(ADA))
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
     * amount split evenly in what the currency has for subunits, the first of them taking what
     * that leaves over, every expense passing the rules.
     */
    private class SplittingCallBacks : CreateExpenseCallBacks {
        override val maxTitleLength = 50
        override val maxDescriptionLength = 500
        override val maxBeneficiaries = 100

        override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? = null

        override fun computeBeneficiaries(
            amount: String,
            currency: Currency,
            beneficiaryUserUuids: List<String>,
            fixedUuid: Map<String, String>,
            sharePercentages: Map<String, String>,
            shareWeights: Map<String, String>,
            expenseUuid: String,
        ): List<Beneficiary> {
            val fixed = fixedUuid.values.fold(BigDecimal.ZERO) { sum, share -> sum + BigDecimal(share) }
            val others = beneficiaryUserUuids.filterNot { it in fixedUuid }
            val left = BigDecimal(amount) - fixed
            val each = if (others.isEmpty()) {
                BigDecimal.ZERO
            } else {
                left.divide(BigDecimal(others.size), if (currency.hasSubunits) 2 else 0, RoundingMode.DOWN)
            }
            val leftOver = left - each * BigDecimal(others.size)

            return beneficiaryUserUuids.map { userUuid ->
                val share = when (userUuid) {
                    in fixedUuid -> fixedUuid.getValue(userUuid)
                    others.first() -> (each + leftOver).toPlainString()
                    else -> each.toPlainString()
                }

                Beneficiary(expenseUuid = expenseUuid, userUuid = userUuid, share = share)
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
