package viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [LoginViewModel.updateUsername] makes of a username typed with capitals, or with a space at
 * the end.
 */
class LoginViewModelTest {

    /**
     * Read straight after the edit, with no dispatcher run in between: the field takes its text
     * back in the same frame. As typed, so that the field never hands the keyboard a new text,
     * and checked lowercased, which the rules refuse a capital in.
     */
    @Test
    fun `the username is held as typed and checked lowercased`() {
        val viewModel = LoginViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        viewModel.updateUsername(username = "Ada4")

        assertEquals("Ada4", viewModel.state.username)
        assertEquals("ada4", viewModel.state.trimmedUsername)
        assertNull(viewModel.state.usernameError)
    }

    /**
     * Trimmed, the space would be swallowed, and the cursor moved back over it. Checked without
     * it, as it is logged in with, the rules refusing a space around a username.
     */
    @Test
    fun `a space typed at the end is held as typed`() {
        val viewModel = LoginViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        viewModel.updateUsername(username = "ada4 ")
        viewModel.updatePassword(password = "correct horse")

        assertEquals("ada4 ", viewModel.state.username)
        assertEquals("ada4", viewModel.state.trimmedUsername)
        assertNull(viewModel.state.usernameError)
        assertTrue(viewModel.state.canLogIn)
    }
}
