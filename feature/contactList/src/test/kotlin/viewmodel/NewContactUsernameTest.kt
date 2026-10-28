package viewmodel

import ContactCallBacks
import kotlinx.coroutines.flow.Flow
import model.AddContactError
import model.AnswerContactRequestError
import model.ContactRequest
import model.ContactVerification
import model.RemoveContactError
import model.User
import model.UsernameInputError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The username the form holds once an edit is taken, see [NewContactViewModel.updateUsername]: the
 * field is given it back in the same frame, so it has to be there as soon as the call returns, and
 * as typed, so that the field never hands the keyboard a new text.
 */
class NewContactUsernameTest {

    private val viewModel = NewContactViewModel(contactCallBacks = TypingCallBacks())

    /** Checked lowercased, which the rules refuse a capital in. */
    @Test
    fun `a username typed with capitals is held as typed and checked lowercased`() {
        viewModel.updateUsername("Alice")

        assertEquals("Alice", viewModel.state.username)
        assertEquals("alice", viewModel.state.trimmedUsername)
        assertNull(viewModel.state.inputError)
    }

    /** Trimmed, the space would be swallowed, and the cursor moved back over it. */
    @Test
    fun `a space typed at the end is held as typed`() {
        viewModel.updateUsername("alice ")

        assertEquals("alice ", viewModel.state.username)
        assertEquals("alice", viewModel.state.trimmedUsername)
    }

    /**
     * What typing a username asks of the app: its length limit, and whether it can be anybody's,
     * which none holding a capital can be, as the library has it.
     */
    private class TypingCallBacks : ContactCallBacks {
        override val maxUsernameLength: Int = 20

        override fun validateUsername(username: String): UsernameInputError? =
            if (username != username.lowercase()) UsernameInputError.INVALID_CHARACTER else null

        override suspend fun getFilteredUsers(filter: String): Flow<List<User>> = notWhileTyping()

        override suspend fun getUser(uuid: String): Flow<User> = notWhileTyping()

        override fun getAuthenticatedUserUuid(): String? = notWhileTyping()

        override suspend fun removeContact(userUuid: String): RemoveContactError? = notWhileTyping()

        override fun getVerification(userUuid: String): Flow<ContactVerification> = notWhileTyping()

        override suspend fun markVerified(userUuid: String, safetyNumber: String) { notWhileTyping() }

        override suspend fun clearVerification(userUuid: String) { notWhileTyping() }

        override fun getReservedUsername(): Flow<String?> = notWhileTyping()

        override fun getContactRequestCount(): Flow<Int> = notWhileTyping()

        override fun getContactRequests(): Flow<List<ContactRequest>> = notWhileTyping()

        override suspend fun refreshContacts() { notWhileTyping() }

        override fun contactsSeen() { notWhileTyping() }

        override fun contactRequestsSeen() { notWhileTyping() }

        override suspend fun acceptContactRequest(requestUuid: String): AnswerContactRequestError? =
            notWhileTyping()

        override suspend fun refuseContactRequest(requestUuid: String): AnswerContactRequestError? =
            notWhileTyping()

        override suspend fun sendContactRequest(username: String): AddContactError? = notWhileTyping()

        private fun notWhileTyping(): Nothing = error("Not asked while a username is typed")
    }
}
