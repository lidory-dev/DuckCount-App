package viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [UserInformationViewModel.updateFullName] makes of a full name typed with whitespace around
 * it, and when it can be saved, see [UserInformationState.canSave]: the profile is saved without
 * that whitespace, by the shared code the form hands the name to.
 */
class UserInformationViewModelTest {

    private val viewModel =
        UserInformationViewModel(authenticationCallBacks = TypingOnlyCallBacks())

    /** Trimmed, the space would be swallowed, and the cursor moved back over it. */
    @Test
    fun `a space typed after a name is held as typed`() {
        viewModel.updateFullName(fullName = "Ada  Lovelace ")

        assertEquals("Ada  Lovelace ", viewModel.state.fullName)
        assertTrue(viewModel.state.canSave)
    }

    @Test
    fun `a full name of nothing but whitespace cannot be saved`() {
        viewModel.updateFullName(fullName = " \n\t ")

        assertFalse(viewModel.state.canSave)
    }
}
