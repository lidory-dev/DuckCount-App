package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupHistoryError
import app.lidory.duckcount.groupList.model.GroupInformationVersion
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** The versions the history gives, the one the group is at first. */
private val VERSIONS = listOf(
    GroupInformationVersion(
        revision = 2,
        title = "Ski trip 2027",
        description = "",
        isCreation = false,
        isCurrent = true,
        revisedAt = 1793214127004L,
    ),
    GroupInformationVersion(
        revision = 1,
        title = "Ski trip",
        description = "Chalet and lift passes",
        isCreation = true,
        isCurrent = false,
        revisedAt = 1793095200000L,
    ),
)

/**
 * What the history screen shows of the group's history, see [GroupHistoryViewModel]: read from the
 * server as the screen first asks, and again when the user asks once it could not be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupHistoryViewModelTest {

    // The ViewModel reads on the main dispatcher, which a JVM test has none of. Nothing it launches
    // runs but what a test runs this dispatcher for: with runCurrent, which runs the screen's
    // collection too, launched in the background, which advanceUntilIdle would leave waiting.
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
    fun `the history is read once the screen asks for it`() = runTest(mainDispatcher) {
        val callBacks = HistoryCallBacks(GroupHistory.Versions(versions = VERSIONS))
        val viewModel = GroupHistoryViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        runCurrent()

        assertEquals(GroupHistoryState.Loading, viewModel.state.value)
        assertTrue(callBacks.asked.isEmpty())

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(GroupHistoryState.Content(versions = VERSIONS), viewModel.state.value)
        assertEquals(listOf(GROUP), callBacks.asked)
    }

    /** Coming back to the screen, from another app say, reads nothing again. */
    @Test
    fun `the history is read once however often the screen asks for it`() = runTest(mainDispatcher) {
        val callBacks = HistoryCallBacks(GroupHistory.Versions(versions = VERSIONS))
        val viewModel = GroupHistoryViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        val first = backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        first.cancel()

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(listOf(GROUP), callBacks.asked)
    }

    @Test
    fun `a history that could not be read tells why`() = runTest(mainDispatcher) {
        val viewModel = GroupHistoryViewModel(
            groupUuid = GROUP,
            groupListCallBacks = HistoryCallBacks(GroupHistory.Unavailable(error = GroupHistoryError.DeviceLocked)),
        )

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(GroupHistoryState.Failed(error = GroupHistoryError.DeviceLocked), viewModel.state.value)
    }

    @Test
    fun `a history that could not be read is read again on retry`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val callBacks = HistoryCallBacks(
            GroupHistory.Unavailable(error = GroupHistoryError.Failure(GroupCreationFailure.NO_INTERNET)),
            GroupHistory.Versions(versions = VERSIONS),
            gates = listOf(null, gate),
        )
        val viewModel = GroupHistoryViewModel(groupUuid = GROUP, groupListCallBacks = callBacks)

        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertEquals(
            GroupHistoryState.Failed(error = GroupHistoryError.Failure(GroupCreationFailure.NO_INTERNET)),
            viewModel.state.value,
        )

        viewModel.retry()
        runCurrent()

        // Shown loading again while it is read.
        assertEquals(GroupHistoryState.Loading, viewModel.state.value)

        gate.complete(Unit)
        runCurrent()

        assertEquals(GroupHistoryState.Content(versions = VERSIONS), viewModel.state.value)
        assertEquals(listOf(GROUP, GROUP), callBacks.asked)
    }
}

/**
 * Answers each read of the history with the next of [answers], once the gate [gates] holds for it,
 * if any, is open, and nothing else the history screen does not ask.
 */
private class HistoryCallBacks(
    vararg answers: GroupHistory,
    private val gates: List<CompletableDeferred<Unit>?> = emptyList(),
) : GroupListCallBacks {

    private val answers = answers.toList()

    /** The group whose history was read, each time it was. */
    val asked = mutableListOf<String>()

    override suspend fun getGroupHistory(uuid: String): GroupHistory {
        val read = asked.size

        asked += uuid
        gates.getOrNull(read)?.await()

        return answers[read]
    }

    override val maxTitleLength: Int get() = TODO()
    override val maxDescriptionLength: Int get() = TODO()
    override val maxMembers: Int get() = TODO()

    override fun getAllGroups(): Flow<List<Group>> = TODO()
    override fun getGroup(uuid: String): Flow<Group> = TODO()
    override fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers?> = TODO()
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
    ): GroupCreationError? = TODO()
    override fun getAuthenticatedUserUuid(): String? = TODO()
    override suspend fun updateGroup(uuid: String, title: String, description: String): GroupEditError? = TODO()
    override suspend fun refreshGroups(): Unit = TODO()
    override fun groupsSeen(): Unit = TODO()
}
