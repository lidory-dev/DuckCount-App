package viewmodel

import PasswordInputError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [RegisterViewModel.updateUsername] makes of a username typed with capitals, or with a space
 * at the end, and which passwords the form registers, see [RegisterViewModel.canRegister].
 */
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
        assertEquals("ada4", viewModel.state.trimmedUsername)
        assertNull(viewModel.state.usernameError)
    }

    /**
     * Trimmed, the space would be swallowed, and the cursor moved back over it. Checked without
     * it, as it is registered, the rules refusing a space around a username.
     */
    @Test
    fun `a space typed at the end is held as typed`() {
        val viewModel = RegisterViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        viewModel.updateUsername(username = "ada4 ")

        assertEquals("ada4 ", viewModel.state.username)
        assertEquals("ada4", viewModel.state.trimmedUsername)
        assertNull(viewModel.state.usernameError)
    }

    @Test
    fun `nothing typed in the password is no mistake`() {
        val viewModel = RegisterViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        assertNull(viewModel.passwordError())
        assertFalse(viewModel.canRegister())
    }

    /** As long as the minimum or longer: no form would log in with it. */
    @Test
    fun `a password of nothing but spaces cannot be registered`() {
        val viewModel = filledIn(password = " ".repeat(20))

        assertEquals(PasswordInputError.BLANK, viewModel.passwordError())
        assertFalse(viewModel.canRegister())
    }

    /** From the keyboard's Done as from the button, which both ask [RegisterViewModel.canRegister]. */
    @Test
    fun `a password too short cannot be registered`() {
        val viewModel = filledIn(password = "abc")

        assertEquals(PasswordInputError.TOO_SHORT, viewModel.passwordError())
        assertFalse(viewModel.canRegister())
    }

    /** A password is taken as typed, never trimmed: the spaces in it are part of it. */
    @Test
    fun `a password with spaces in it can be registered`() {
        val viewModel = filledIn(password = " correct horse battery ")

        assertNull(viewModel.passwordError())
        assertTrue(viewModel.canRegister())
    }

    @Test
    fun `a password typed again differently cannot be registered`() {
        val viewModel = filledIn(password = "correct horse battery")

        viewModel.updatePasswordConfirmation(password = "correct horse batterY")

        assertFalse(viewModel.canRegister())
    }

    /** Whitespace is part of a password, so it is a confirmation that does not match. */
    @Test
    fun `a confirmation of nothing but spaces that differs is pointed out`() {
        val viewModel = filledIn(password = "correct horse battery")

        viewModel.updatePasswordConfirmation(password = "   ")

        assertTrue(viewModel.isPasswordConfirmationMismatched())
    }

    /** The form with a username the rules take, and [password] typed in both password fields. */
    private fun filledIn(password: String) =
        RegisterViewModel(authenticationCallBacks = TypingOnlyCallBacks()).apply {
            updateUsername(username = "ada4")
            updatePassword(password = password)
            updatePasswordConfirmation(password = password)
        }
}
