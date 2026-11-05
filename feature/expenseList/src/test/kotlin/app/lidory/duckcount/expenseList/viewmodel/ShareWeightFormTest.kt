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
private const val CHARLIE = "33333333-3333-3333-3333-333333333333"

private const val EXPENSE = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"

private val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)

/**
 * A share set as a weight as the form holds it: handed to the library as what was set by hand alone,
 * what it comes to following the amount, and replaced by anything else set over it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareWeightFormTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A new expense of [amount] for Alice, Bob and Charlie, read and ready to be filled in. */
    private fun newForm(
        callBacks: ShareCalculatingCallBacks = ShareCalculatingCallBacks(),
        amount: String = "100.00",
    ): CreateExpenseViewModel {
        val viewModel = CreateExpenseViewModel(groupUuid = null, expenseUuid = null, createExpenseCallBacks = callBacks)

        mainDispatcher.scheduler.runCurrent()

        viewModel.updateAmount(amount)
        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB, CHARLIE))

        return viewModel
    }

    private fun CreateExpenseViewModel.beneficiary(userUuid: String): Beneficiary =
        state.beneficiaries.single { it.userUuid == userUuid }

    @Test
    fun `two parts for Alice and one each for the others of 100 is 50 and 25 each`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")

        assertEquals(mapOf(ALICE to "2"), callBacks.lastWeights)
        assertEquals("50.00", viewModel.beneficiary(ALICE).share)
        assertEquals("25.00", viewModel.beneficiary(BOB).share)
        assertEquals("25.00", viewModel.beneficiary(CHARLIE).share)
    }

    /** Which is what tells a weight set by hand from one the library only states. */
    @Test
    fun `the others count for one part without it having been set for them`() {
        val viewModel = newForm()

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")

        assertEquals("2", viewModel.beneficiary(ALICE).shareWeight)
        assertTrue(viewModel.beneficiary(ALICE).isShareFixed)
        assertEquals("1", viewModel.beneficiary(BOB).shareWeight)
        assertFalse(viewModel.beneficiary(BOB).isShareFixed)
    }

    @Test
    fun `the whitespace around a weight is not kept`() {
        val viewModel = newForm()

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = " 2 ")

        assertEquals("2", viewModel.beneficiary(ALICE).shareWeight)
    }

    /** As the screen works them out at each amount typed. */
    @Test
    fun `shares set as weights follow the amount`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")

        viewModel.updateAmount("200.00")
        viewModel.calculateShares()

        assertEquals(mapOf(ALICE to "2"), callBacks.lastWeights)
        assertEquals("100.00", viewModel.beneficiary(ALICE).share)
        assertEquals("50.00", viewModel.beneficiary(BOB).share)
    }

    @Test
    fun `weights share what a fixed amount and a percentage leave`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB, CHARLIE, "44444444-4444-4444-4444-444444444444"))
        viewModel.typeBeneficiaryShare(userUuid = ALICE, text = "10")
        viewModel.setBeneficiaryFixedShare(userUuid = ALICE)
        viewModel.setBeneficiaryPercentage(userUuid = BOB, percentage = "30")
        viewModel.setBeneficiaryWeight(userUuid = CHARLIE, weight = "2")

        assertEquals(mapOf(ALICE to "10.00"), callBacks.lastFixedShares)
        assertEquals(mapOf(BOB to "30"), callBacks.lastPercentages)
        assertEquals(mapOf(CHARLIE to "2"), callBacks.lastWeights)
        // The 60 they leave, in three parts.
        assertEquals("40.00", viewModel.beneficiary(CHARLIE).share)
    }

    /** As the screen does for an amount typed in a share's field. */
    @Test
    fun `an amount typed over a weight replaces it`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")

        viewModel.typeBeneficiaryShare(userUuid = ALICE, text = "40")
        viewModel.setBeneficiaryFixedShare(userUuid = ALICE)
        viewModel.calculateShares()

        assertEquals(mapOf(ALICE to "40.00"), callBacks.lastFixedShares)
        assertEquals(emptyMap<String, String>(), callBacks.lastWeights)
        assertNull(viewModel.beneficiary(ALICE).shareWeight)
        assertTrue(viewModel.beneficiary(ALICE).isShareFixed)
    }

    @Test
    fun `a percentage set over a weight replaces it and a weight over a percentage too`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")
        viewModel.setBeneficiaryPercentage(userUuid = ALICE, percentage = "50")

        assertEquals(mapOf(ALICE to "50"), callBacks.lastPercentages)
        assertEquals(emptyMap<String, String>(), callBacks.lastWeights)
        assertNull(viewModel.beneficiary(ALICE).shareWeight)

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")

        assertEquals(emptyMap<String, String>(), callBacks.lastPercentages)
        assertEquals(mapOf(ALICE to "2"), callBacks.lastWeights)
        assertNull(viewModel.beneficiary(ALICE).sharePercentage)
    }

    @Test
    fun `taking the last weight off leaves an even split and no weights at all`() {
        val viewModel = newForm()

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "2")
        viewModel.removeBeneficiaryFixedShare(userUuid = ALICE)

        listOf(ALICE, BOB, CHARLIE).forEach { userUuid ->
            assertEquals("33.33", viewModel.beneficiary(userUuid).share)
            assertNull(viewModel.beneficiary(userUuid).shareWeight)
            assertFalse(viewModel.beneficiary(userUuid).isShareFixed)
        }
    }

    @Test
    fun `a weight stays as the others are selected and goes with who is unselected`() {
        val callBacks = ShareCalculatingCallBacks()
        val viewModel = newForm(callBacks)

        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB))
        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "3")

        assertEquals("75.00", viewModel.beneficiary(ALICE).share)

        viewModel.onBeneficiariesSelected(setOf(ALICE, BOB, CHARLIE))

        assertEquals(mapOf(ALICE to "3"), callBacks.lastWeights)
        assertEquals("60.00", viewModel.beneficiary(ALICE).share)
        assertEquals("3", viewModel.beneficiary(ALICE).shareWeight)
        assertTrue(viewModel.beneficiary(ALICE).isShareFixed)
        assertEquals("1", viewModel.beneficiary(CHARLIE).shareWeight)

        viewModel.onBeneficiariesSelected(setOf(BOB, CHARLIE))

        assertEquals(emptyMap<String, String>(), callBacks.lastWeights)
        assertNull(viewModel.beneficiary(BOB).shareWeight)
        assertEquals("50.00", viewModel.beneficiary(BOB).share)
    }

    /** It saves differently, though the shares come to the same: the next change would show it. */
    @Test
    fun `a weight that changes no share is a change`() {
        val viewModel = newForm()
        val withNone = viewModel.state

        viewModel.setBeneficiaryWeight(userUuid = ALICE, weight = "1")

        assertEquals(withNone.beneficiaries.map { it.share }.toSet(), setOf(viewModel.beneficiary(BOB).share))
        assertTrue(viewModel.state.isChangedFrom(withNone))
    }

    @Test
    fun `an expense read with weights keeps them through an edit of its amount`() = runTest(mainDispatcher) {
        val saved = mutableListOf<ExpenseWithBeneficiaries>()

        val callBacks = object : ShareCalculatingCallBacks() {
            override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> =
                flowOf(
                    ExpenseWithBeneficiaries(
                        uuid = EXPENSE,
                        timestamp = "2026-10-30T19:45:00",
                        title = "Dinner",
                        description = "",
                        amount = "90.00",
                        creditorUuid = ALICE,
                        isSettlement = false,
                        currency = EURO,
                        beneficiaries = listOf(
                            Beneficiary(
                                expenseUuid = EXPENSE,
                                userUuid = ALICE,
                                share = "60.00",
                                isShareFixed = true,
                                shareWeight = "2",
                            ),
                            Beneficiary(expenseUuid = EXPENSE, userUuid = BOB, share = "30.00", shareWeight = "1"),
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

        viewModel.updateAmount("30.00")
        viewModel.calculateShares()
        viewModel.updateExpense()
        runCurrent()

        assertEquals(mapOf(ALICE to "2"), callBacks.lastWeights)

        val shares = saved.single().beneficiaries.associateBy { it.userUuid }

        assertEquals("20.00", shares.getValue(ALICE).share)
        assertEquals("2", shares.getValue(ALICE).shareWeight)
        assertEquals("10.00", shares.getValue(BOB).share)
        assertEquals("1", shares.getValue(BOB).shareWeight)
    }
}
