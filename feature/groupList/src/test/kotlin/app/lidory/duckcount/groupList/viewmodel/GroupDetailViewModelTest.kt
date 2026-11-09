package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationResult
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * What opening a group does beyond showing it, see [GroupDetailViewModel]: its expenses are synced,
 * and how many of them this device cannot read yet is shown with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

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

    @Test
    fun `opening a group syncs its expenses`() = runTest(mainDispatcher) {
        val callBacks = DetailCallBacks()
        val viewModel = GroupDetailViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        runCurrent()

        assertTrue(callBacks.refreshed.isEmpty())

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(listOf(GROUP), callBacks.refreshed)
    }

    /** The group shows what this device has of it while the server is asked. */
    @Test
    fun `the group shows before its expenses are synced`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val callBacks = DetailCallBacks(gate = gate)
        val viewModel = GroupDetailViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(GROUP, viewModel.state.value.groupWithMembers?.uuid)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(GROUP), callBacks.refreshed)
    }

    /** Sealed under a key this device has yet to get: the group's screen says its balances leave them out. */
    @Test
    fun `the group shows how many of its expenses cannot be read yet`() = runTest(mainDispatcher) {
        val unreadable = MutableStateFlow(0)
        val callBacks = DetailCallBacks(unreadable = unreadable)
        val viewModel = GroupDetailViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(0, viewModel.state.value.unreadableExpenseCount)

        unreadable.value = 2
        runCurrent()

        assertEquals(2, viewModel.state.value.unreadableExpenseCount)
    }

    /**
     * Back to the group after a while away, an expense read or the app in the background: its
     * expenses are synced again, and what it shows is still read once.
     */
    @Test
    fun `coming back to the group reads it once still`() = runTest(mainDispatcher) {
        val unreadable = MutableStateFlow(0)
        val callBacks = DetailCallBacks(unreadable = unreadable)
        val viewModel = GroupDetailViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        repeat(3) {
            val shown = backgroundScope.launch { viewModel.state.collect {} }
            runCurrent()

            shown.cancel()
            advanceTimeBy(6_000L)
            runCurrent()
        }

        assertEquals(1, unreadable.subscriptionCount.value)
        assertEquals(listOf(GROUP, GROUP, GROUP), callBacks.refreshed)
    }
}

/**
 * Shows the group [GROUP], with [unreadable] of its expenses this device cannot read yet, and
 * records each sync of its expenses, once [gate], if any, is open, and nothing else the group's
 * screen does not ask.
 */
private class DetailCallBacks(
    private val gate: CompletableDeferred<Unit>? = null,
    private val unreadable: Flow<Int> = flowOf(0),
) : GroupListCallBacks {

    /** The groups whose expenses were synced, each time they were. */
    val refreshed = mutableListOf<String>()

    override fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers?> = flowOf(
        GroupWithMembers(
            uuid = uuid,
            description = "",
            title = "Ski trip",
            currency = Currency(uuid = "f4c08b14-5847-4d50-87b0-6ca4f3e961e1", name = "EUR", symbol = "€", hasSubunits = true),
            members = emptyList(),
            isLeft = false,
            hasPendingChange = false,
            canChangeMembers = false,
            canEdit = false,
            canVouchForMembers = true,
            canLeave = true,
        ),
    )

    override suspend fun refreshGroupExpenses(uuid: String) {
        gate?.await()

        refreshed += uuid
    }

    override fun getUnreadableExpenseCount(uuid: String): Flow<Int> = unreadable

    override val maxTitleLength: Int get() = TODO()
    override val maxDescriptionLength: Int get() = TODO()
    override val maxMembers: Int get() = TODO()

    override fun getAllGroups(): Flow<List<Group>> = TODO()
    override fun getGroup(uuid: String): Flow<Group> = TODO()
    override fun getInvitableUsersFiltered(filter: String): Flow<List<User>> = TODO()
    override fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = TODO()
    override fun getUserFullName(uuid: String): Flow<String?> = TODO()
    override fun getCurrencies(): List<Currency> = TODO()
    override fun getDefaultCurrency(): Currency = TODO()
    override fun getCurrencyDisplayName(code: String): String = TODO()
    override suspend fun createGroup(
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMember>,
    ): GroupCreationResult = TODO()
    override fun getAuthenticatedUserUuid(): String? = TODO()
    override suspend fun updateGroup(uuid: String, title: String, description: String): GroupEditError? = TODO()
    override suspend fun getGroupHistory(uuid: String): GroupHistory = TODO()
    override suspend fun refreshGroups(): Unit = TODO()
    override fun groupsSeen(): Unit = TODO()
}
