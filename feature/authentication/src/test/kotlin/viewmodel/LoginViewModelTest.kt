package viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the username field gets back from [LoginViewModel.updateUsername]. */
class LoginViewModelTest {

    /**
     * Read straight after the edit, with no dispatcher run in between: the field takes its text
     * back in the same frame, or the letters typed next land out of place.
     */
    @Test
    fun `the username typed is held lowercased right after the edit`() {
        val viewModel = LoginViewModel(authenticationCallBacks = TypingOnlyCallBacks())

        viewModel.updateUsername(username = "Ada4")

        assertEquals("ada4", viewModel.state.username)
    }
}
