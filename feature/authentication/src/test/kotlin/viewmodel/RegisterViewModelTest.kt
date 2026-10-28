package viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What [RegisterViewModel.updateUsername] makes of a username typed with capitals. */
class RegisterViewModelTest {

    /**
     * Read straight after the edit, with no dispatcher run in between: the field takes its text
     * back in the same frame. As typed, so that the field never hands the keyboard a new text,
     * and checked lowercased, which the rules refuse a capital in.
     */
    @Test
    fun `the username is held as typed and checked lowercased`() {
        val viewModel = RegisterViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        viewModel.updateUsername(username = "Ada4")

        assertEquals("Ada4", viewModel.state.username)
        assertEquals("ada4", viewModel.state.lowercasedUsername)
        assertNull(viewModel.state.usernameError)
    }
}
