package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.callBacks.GroupSettleUpCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.Transaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val ALICE = "alice"
private const val BOB = "bob"

/**
 * What becomes of Alice recording that Bob paid her back, see [GroupSettleUpViewModel.validateSettlement]:
 * written on the server first, as every expense of a group is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSettleUpViewModelTest {

    // As in GroupHistoryViewModelTest: nothing the ViewModel launches runs but what a test runs
    // this dispatcher for.
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Each one is an expense of its own: a second tap would record the debt paid back twice. */
    @Test
    fun `a settlement tapped again while on its way is recorded once`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val callBacks = SettleUpCallBacks(gate = gate)
        val viewModel = GroupSettleUpViewModel(groupUuid = GROUP, groupSettleUpCallBacks = callBacks)

        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(BOB), callBacks.recorded)
    }

    @Test
    fun `a settlement that was not recorded tells why`() = runTest(mainDispatcher) {
        val viewModel = GroupSettleUpViewModel(
            groupUuid = GROUP,
            groupSettleUpCallBacks = SettleUpCallBacks(answer = GroupCreationFailure.NO_INTERNET),
        )
        val errors = mutableListOf<GroupCreationFailure>()

        backgroundScope.launch { viewModel.showError.collect { errors += it } }

        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        runCurrent()

        assertEquals(listOf(GroupCreationFailure.NO_INTERNET), errors)
    }

    /** The debt is still listed, for the user to record again once the first answer came. */
    @Test
    fun `a settlement that was not recorded can be recorded again`() = runTest(mainDispatcher) {
        val callBacks = SettleUpCallBacks(answer = GroupCreationFailure.SERVER_UNREACHABLE)
        val viewModel = GroupSettleUpViewModel(groupUuid = GROUP, groupSettleUpCallBacks = callBacks)

        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        runCurrent()
        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        runCurrent()

        assertEquals(listOf(BOB, BOB), callBacks.recorded)
    }

    @Test
    fun `a settlement recorded tells nothing`() = runTest(mainDispatcher) {
        val viewModel = GroupSettleUpViewModel(groupUuid = GROUP, groupSettleUpCallBacks = SettleUpCallBacks())
        val errors = mutableListOf<GroupCreationFailure>()

        backgroundScope.launch { viewModel.showError.collect { errors += it } }

        viewModel.validateSettlement(creditorUuid = ALICE, debtorUuid = BOB, amount = "20.00")
        runCurrent()

        assertTrue(errors.isEmpty())
    }
}

/**
 * Answers each settlement with [answer], once [gate], if any, is open, recording who paid each, and
 * shows a group owing nothing.
 */
private class SettleUpCallBacks(
    private val answer: GroupCreationFailure? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : GroupSettleUpCallBacks {

    /** Who paid the user back, for each settlement recorded. */
    val recorded = mutableListOf<String>()

    override suspend fun validateSettlement(
        creditorUuid: String,
        debtorUuid: String,
        amount: String,
        groupUuid: String,
    ): GroupCreationFailure? {
        recorded += debtorUuid
        gate?.await()

        return answer
    }

    override suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): List<Transaction> = emptyList()

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMember>> = flowOf(emptyList())
    override fun getAllGroupActiveExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> = flowOf(emptyList())
    override fun getAuthenticatedUserUuid(): String = ALICE
    override fun isGroupLeft(groupUuid: String): Flow<Boolean> = flowOf(false)
    override suspend fun getCurrencySymbol(groupUuid: String): String = "€"
    override fun getDisplayName(userUuid: String): Flow<String?> = flowOf(null)
}
