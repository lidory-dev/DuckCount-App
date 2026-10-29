package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private const val TITLE = "Ski trip"
private const val DESCRIPTION = "Chalet and lift passes"

/**
 * When the form of a group being edited can be confirmed, see [CreateGroupViewModel]: once what it
 * holds is other than what the group shows, read again each time that changes, whatever the user
 * typed meanwhile, which the form keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditFormTest {

    // The ViewModel reads the group on the main dispatcher, which a JVM test has none of, and only
    // as a test runs this dispatcher for it.
    private val mainDispatcher = StandardTestDispatcher()

    /** The group as this device shows it, see [GroupListCallBacks.getGroupWithMembers]. */
    private val group = MutableStateFlow<GroupWithMembers?>(groupShowing(title = TITLE, description = DESCRIPTION))

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the form opens with what the group shows and cannot be confirmed as it is`() = runTest(mainDispatcher) {
        val viewModel = form()

        assertEquals(TITLE, viewModel.content().title)
        assertEquals(DESCRIPTION, viewModel.content().description)
        assertFalse(viewModel.canConfirm())
    }

    @Test
    fun `a change typed can be confirmed until it is typed back`() = runTest(mainDispatcher) {
        val viewModel = form()

        viewModel.updateTitle("Ski trip 2027")
        assertTrue(viewModel.canConfirm())

        viewModel.updateTitle(TITLE)
        assertFalse(viewModel.canConfirm())

        viewModel.updateDescription("")
        assertTrue(viewModel.canConfirm())
    }

    /** Held as typed, for the field, and saved without it, which would change nothing. */
    @Test
    fun `a space typed after the title is held and changes nothing`() = runTest(mainDispatcher) {
        val viewModel = form()

        viewModel.updateTitle("$TITLE ")

        assertEquals("$TITLE ", viewModel.content().title)
        assertFalse(viewModel.canConfirm())
    }

    /**
     * As an edit refused as outdated shows the version the server holds, or a sync an earlier edit
     * of the user's whose answer was lost.
     */
    @Test
    fun `what the group shows since is what the form is compared with`() = runTest(mainDispatcher) {
        val viewModel = form()

        viewModel.updateTitle("Ski trip 2027")

        group.value = groupShowing(title = "Ski trip 2027", description = DESCRIPTION)
        runCurrent()

        assertEquals("Ski trip 2027", viewModel.content().title)
        assertFalse(viewModel.canConfirm())

        viewModel.updateTitle(TITLE)
        assertTrue(viewModel.canConfirm())
    }

    private fun TestScope.form(): CreateGroupViewModel {
        val viewModel = CreateGroupViewModel(groupUuid = GROUP, groupListCallBacks = EditCallBacks(group))

        runCurrent()

        return viewModel
    }

    private fun CreateGroupViewModel.content() = state as CreateGroupState.Content

    private fun CreateGroupViewModel.canConfirm() =
        content().canConfirm(maxTitleLength = maxTitleLength, maxDescriptionLength = maxDescriptionLength)
}

/** The group, created by the user, showing [title] and [description]. */
private fun groupShowing(title: String, description: String) = GroupWithMembers(
    uuid = GROUP,
    description = description,
    title = title,
    currency = Currency(uuid = "f4c08b14-5847-4d50-87b0-6ca4f3e961e1", name = "EUR", symbol = "€", hasSubunits = true),
    members = emptyList(),
    isLeft = false,
    hasPendingChange = false,
    canChangeMembers = true,
    canEdit = true,
    canVouchForMembers = true,
    canLeave = false,
)

/** Shows the group as [group] holds it, and nothing else the form of a group being edited does not ask. */
private class EditCallBacks(private val group: Flow<GroupWithMembers?>) : GroupListCallBacks {

    override val maxTitleLength: Int = 50
    override val maxDescriptionLength: Int = 2000
    override val maxMembers: Int = 100

    override fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers?> = group
    override fun getCurrencies(): List<Currency> = emptyList()
    override fun getAuthenticatedUserUuid(): String? = "self"

    override fun getAllGroups(): Flow<List<Group>> = TODO()
    override fun getGroup(uuid: String): Flow<Group> = TODO()
    override fun getInvitableUsersFiltered(filter: String): Flow<List<User>> = TODO()
    override fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = TODO()
    override fun getUserFullName(uuid: String): Flow<String?> = TODO()
    override fun getDefaultCurrency(): Currency = TODO()
    override fun getCurrencyDisplayName(code: String): String = TODO()
    override suspend fun createGroup(
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMember>,
    ): GroupCreationError? = TODO()
    override suspend fun updateGroup(uuid: String, title: String, description: String): GroupEditError? = TODO()
    override suspend fun getGroupHistory(uuid: String): GroupHistory = TODO()
    override suspend fun refreshGroups(): Unit = TODO()
    override fun groupsSeen(): Unit = TODO()
}
