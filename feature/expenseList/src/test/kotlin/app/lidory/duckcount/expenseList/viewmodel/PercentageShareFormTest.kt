package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val EXPENSE = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"

private val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)

/**
 * A share set as a percentage of the amount as the form holds it: handed to the library as a
 * percentage, what it comes to following the amount, and replaced by an amount typed over it or
 * by taking the share off by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PercentageShareFormTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A new expense of [amount] for Alice and Bob, read and ready to be filled in. */
    private fun newForm(callBacks: ShareCalculatingCallBacks = ShareCalculatingCallBacks(), amount: String = "100.00"): CreateExpenseViewModel {
        val viewModel = CreateExpenseViewModel(groupUuid = null, expenseUuid = null, createExpenseCallBacks = callBacks)

        mainDispatcher.scheduler.runCurrent()

        viewModel.updateAmount(amount)
        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB))

        return viewModel
    }

    private fun CreateExpenseViewModel.beneficiary(userUuid: String): Beneficiary =
        state.beneficiaries.single { it.userUuid == userUuid }

    @Test
    fun `a percentage is handed over as one and not as an amount`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        assertEquals(mapOf(BOB to "25"), callBacks.lastPercentages)
        assertEquals(emptyMap<String, String>(), callBacks.lastFixedShares)
        assertEquals("25.00", viewModel.beneficiary(BOB).share)
        assertEquals("25", viewModel.beneficiary(BOB).sharePercentage)
        assertTrue(viewModel.beneficiary(BOB).isShareFixed)
        assertEquals("75.00", viewModel.beneficiary(ALICE).share)
        assertNull(viewModel.beneficiary(ALICE).sharePercentage)
        assertFalse(viewModel.beneficiary(ALICE).isShareFixed)
    }

    @Test
    fun `the whitespace around a percentage is not kept`() {
        val viewModel = newForm()

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = " 25 ")

        assertEquals("25", viewModel.beneficiary(BOB).sharePercentage)
    }

    /** As the screen works them out at each amount typed. */
    @Test
    fun `a share set as a percentage follows the amount`() {
        val viewModel = newForm()

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        viewModel.updateAmount("200.00")
        viewModel.calculateShares()

        assertEquals("50.00", viewModel.beneficiary(BOB).share)
        assertEquals("25", viewModel.beneficiary(BOB).sharePercentage)
        assertEquals("150.00", viewModel.beneficiary(ALICE).share)
    }

    @Test
    fun `a fixed amount stays as it is next to a percentage`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB, CAROL))
        viewModel.typeBeneficiaryShare(userUuid = ALICE, text = "10")
        viewModel.setBeneficiaryFixedShare(userUuid = ALICE)
        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        assertEquals(mapOf(ALICE to "10"), callBacks.lastFixedShares)
        assertEquals(mapOf(BOB to "25"), callBacks.lastPercentages)
        assertEquals("65.00", viewModel.beneficiary(CAROL).share)
    }

    /** As the screen does for an amount typed in a share's field. */
    @Test
    fun `an amount typed over a percentage replaces it`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        viewModel.typeBeneficiaryShare(userUuid = BOB, text = "30")
        viewModel.setBeneficiaryFixedShare(userUuid = BOB)
        viewModel.calculateShares()

        assertEquals(mapOf(BOB to "30"), callBacks.lastFixedShares)
        assertEquals(emptyMap<String, String>(), callBacks.lastPercentages)
        assertEquals("30", viewModel.beneficiary(BOB).share)
        assertNull(viewModel.beneficiary(BOB).sharePercentage)
        assertTrue(viewModel.beneficiary(BOB).isShareFixed)
    }

    @Test
    fun `a percentage set over a typed amount replaces it and what was typed`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.typeBeneficiaryShare(userUuid = BOB, text = "30")
        viewModel.setBeneficiaryFixedShare(userUuid = BOB)

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        assertEquals(emptyMap<String, String>(), callBacks.lastFixedShares)
        assertEquals("25.00", viewModel.beneficiary(BOB).share)
        assertEquals(emptyMap<String, String>(), viewModel.state.shareTexts)
    }

    @Test
    fun `taking a fixed share off takes its percentage off too`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")
        viewModel.removeBeneficiaryFixedShare(userUuid = BOB)

        assertEquals(emptyMap<String, String>(), callBacks.lastPercentages)
        assertEquals("50.00", viewModel.beneficiary(BOB).share)
        assertNull(viewModel.beneficiary(BOB).sharePercentage)
        assertFalse(viewModel.beneficiary(BOB).isShareFixed)
    }

    @Test
    fun `a percentage stays as the others are selected and goes with who is unselected`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB, CAROL))

        assertEquals(mapOf(BOB to "25"), callBacks.lastPercentages)
        assertEquals("25.00", viewModel.beneficiary(BOB).share)
        assertEquals("25", viewModel.beneficiary(BOB).sharePercentage)
        assertTrue(viewModel.beneficiary(BOB).isShareFixed)
        assertEquals("37.50", viewModel.beneficiary(CAROL).share)

        viewModel.onBeneficiariesSelected(setOf(ALICE, CAROL))

        assertEquals(emptyMap<String, String>(), callBacks.lastPercentages)
        assertEquals("50.00", viewModel.beneficiary(CAROL).share)
    }

    /** It saves differently, though the share comes to the same: the next amount would show it. */
    @Test
    fun `a share set as the percentage it already comes to is a change`() {
        val viewModel = newForm()

        viewModel.typeBeneficiaryShare(userUuid = BOB, text = "25.00")
        viewModel.setBeneficiaryFixedShare(userUuid = BOB)
        viewModel.finishBeneficiaryShare(userUuid = BOB)
        viewModel.calculateShares()
        val asAnAmount = viewModel.state

        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "25")

        assertEquals(asAnAmount.beneficiaries.single { it.userUuid == BOB }.share, viewModel.beneficiary(BOB).share)
        assertTrue(viewModel.state.isChangedFrom(asAnAmount))
    }

    @Test
    fun `an expense read with a percentage keeps it through an edit of its amount`() = runTest(mainDispatcher) {
        val saved = mutableListOf<ExpenseWithBeneficiaries>()

        val callBacks = object : ShareCalculatingCallBacks() {
            override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> =
                flowOf(
                    ExpenseWithBeneficiaries(
                        uuid = EXPENSE,
                        timestamp = "2026-10-30T19:45:00",
                        title = "Dinner",
                        description = "",
                        amount = "100.00",
                        creditorUuid = ALICE,
                        isSettlement = false,
                        currency = EURO,
                        beneficiaries = listOf(
                            Beneficiary(expenseUuid = EXPENSE, userUuid = ALICE, share = "75.00"),
                            Beneficiary(
                                expenseUuid = EXPENSE,
                                userUuid = BOB,
                                share = "25.00",
                                isShareFixed = true,
                                sharePercentage = "25",
                            ),
                        ),
                        groupUuid = null,
                        isDeleted = false,
                    ),
                )

            override suspend fun updateExpenseWithBeneficiaries(
                expenseWithBeneficiaries: ExpenseWithBeneficiaries,
            ): ExpenseSaveError? {
                saved += expenseWithBeneficiaries

                return null
            }
        }

        val viewModel = CreateExpenseViewModel(groupUuid = null, expenseUuid = EXPENSE, createExpenseCallBacks = callBacks)

        runCurrent()

        assertEquals("25", viewModel.beneficiary(BOB).sharePercentage)

        viewModel.updateAmount("80.00")
        viewModel.calculateShares()
        viewModel.updateExpense()
        runCurrent()

        val bob = saved.single().beneficiaries.single { it.userUuid == BOB }

        assertEquals("20.00", bob.share)
        assertEquals("25", bob.sharePercentage)
    }
}
