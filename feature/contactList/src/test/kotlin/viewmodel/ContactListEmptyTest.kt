package viewmodel

import ContactCallBacks
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import model.AddContactError
import model.AnswerContactRequestError
import model.BlockedUser
import model.ContactRequest
import model.ContactVerification
import model.RemoveContactError
import model.User
import model.UsernameInputError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the contacts tab knows when it lists nobody, see [ContactListState.Content.hasContacts]: that
 * a search found nobody, or that the user has nobody to find, whatever they search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListEmptyTest {

    // The ViewModel reads the contacts on the main dispatcher, which a JVM test has none of, and
    // only as a test runs this dispatcher for it.
    private val mainDispatcher = StandardTestDispatcher()

    private val self = User(uuid = "self", username = "self", fullName = "Me", profilePicture = null)
    private val bruno = User(uuid = "bruno", username = "bruno", fullName = "Bruno", profilePicture = null)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a search that finds nobody is told apart from having no contacts`() = runTest(mainDispatcher) {
        val viewModel = ContactListViewModel(contactCallBacks = ListCallBacks(users = listOf(self, bruno)))
        val state = listed(viewModel)

        search(viewModel, "ffsef")

        assertEquals(emptyList<User>(), state().users)
        assertTrue(state().hasContacts)
    }

    /** Listed by the same query as the contacts, the user is no contact of their own. */
    @Test
    fun `a user with nobody but themself has no contacts`() = runTest(mainDispatcher) {
        val viewModel = ContactListViewModel(contactCallBacks = ListCallBacks(users = listOf(self)))
        val state = listed(viewModel)

        assertFalse(state().hasContacts)

        search(viewModel, "ffsef")

        assertFalse(state().hasContacts)
    }

    /** Offered at the end of the list, whatever it lists. */
    @Test
    fun `how many people the user blocked is told whatever is searched`() = runTest(mainDispatcher) {
        val blocked = listOf(
            BlockedUser(userUuid = "mallory", fullName = "Mallory"),
            BlockedUser(userUuid = "trent", fullName = null),
        )
        val viewModel = ContactListViewModel(
            contactCallBacks = ListCallBacks(users = listOf(self, bruno), blocked = blocked),
        )
        val state = listed(viewModel)

        assertEquals(2, state().blockedUserCount)

        search(viewModel, "ffsef")

        assertEquals(2, state().blockedUserCount)
    }

    @Test
    fun `a contact found is listed`() = runTest(mainDispatcher) {
        val viewModel = ContactListViewModel(contactCallBacks = ListCallBacks(users = listOf(self, bruno)))
        val state = listed(viewModel)

        search(viewModel, "bru")

        assertEquals(listOf(bruno), state().users)
        assertTrue(state().hasContacts)
    }

    /**
     * Types [query] in the search bar. Its snapshot state reaches the ViewModel's flow once applied,
     * which the app's frames do and a test has to.
     */
    private fun TestScope.search(viewModel: ContactListViewModel, query: String) {
        viewModel.onSearchQueryChange(query)
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    /** Collects the tab's state for the rest of the test, as the screen does, and reads it. */
    private fun TestScope.listed(viewModel: ContactListViewModel): () -> ContactListState.Content {
        backgroundScope.launch { viewModel.usersState.collect {} }
        runCurrent()

        return { viewModel.usersState.value as ContactListState.Content }
    }

    /**
     * The contacts tab's view of [users], the user among them as the database lists them, searched
     * by name as the database does, with a username reserved, no request waiting, and the [blocked].
     */
    private class ListCallBacks(
        users: List<User>,
        private val blocked: List<BlockedUser> = emptyList(),
    ) : ContactCallBacks {
        private val users = MutableStateFlow(users)

        override val maxUsernameLength: Int = 20

        override suspend fun getFilteredUsers(filter: String): Flow<List<User>> =
            users.map { all -> all.filter { it.fullName.contains(filter.trim(), ignoreCase = true) } }

        override fun getAuthenticatedUserUuid(): String = "self"

        override fun getReservedUsername(): Flow<String?> = flowOf("me")

        override fun getContactRequestCount(): Flow<Int> = flowOf(0)

        override suspend fun refreshContacts() = Unit

        override fun contactsSeen() = Unit

        override suspend fun getUser(uuid: String): Flow<User> = notOnTheList()

        override suspend fun removeContact(userUuid: String): RemoveContactError? = notOnTheList()

        override suspend fun blockContact(userUuid: String): RemoveContactError? = notOnTheList()

        override fun getBlockedUsers(): Flow<List<BlockedUser>> = flowOf(blocked)

        override suspend fun unblock(userUuid: String) { notOnTheList() }

        override fun getVerification(userUuid: String): Flow<ContactVerification> = notOnTheList()

        override suspend fun markVerified(userUuid: String, safetyNumber: String) { notOnTheList() }

        override suspend fun clearVerification(userUuid: String) { notOnTheList() }

        override fun getContactRequests(): Flow<List<ContactRequest>> = notOnTheList()

        override fun contactRequestsSeen() { notOnTheList() }

        override suspend fun acceptContactRequest(requestUuid: String): AnswerContactRequestError? =
            notOnTheList()

        override suspend fun blockContactRequestSender(requestUuid: String): AnswerContactRequestError? =
            notOnTheList()

        override suspend fun refuseContactRequest(requestUuid: String): AnswerContactRequestError? =
            notOnTheList()

        override fun validateUsername(username: String): UsernameInputError? = notOnTheList()

        override suspend fun sendContactRequest(username: String): AddContactError? = notOnTheList()

        private fun notOnTheList(): Nothing = error("Not asked by the contacts tab")
    }
}
