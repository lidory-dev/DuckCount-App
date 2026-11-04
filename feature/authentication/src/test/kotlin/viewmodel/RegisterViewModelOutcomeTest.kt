package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Where [RegisterViewModel.processRegistration] takes the answer to a registration that failed. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelOutcomeTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a refused phone goes to a screen of its own and leaves no error on the form`() {
        var screensShown = 0
        val viewModel = RegisterViewModel(authenticationCallBacks = Failing(AuthError.DEVICE_NOT_ALLOWED))

        viewModel.register(navigateToDeviceNotAllowedScreen = { screensShown++ })

        assertEquals(1, screensShown)
        assertNull(viewModel.state.error)
        assertFalse(viewModel.state.isLoading)
    }

    /** That one included, which trying again may change: the phone could not attest, it was not refused. */
    @Test
    fun `a phone that could not attest stays on the form with an error`() {
        var screensShown = 0
        val viewModel = RegisterViewModel(authenticationCallBacks = Failing(AuthError.DEVICE_CHECK_FAILED))

        viewModel.register(navigateToDeviceNotAllowedScreen = { screensShown++ })

        assertEquals(0, screensShown)
        assertEquals(AuthError.DEVICE_CHECK_FAILED, viewModel.state.error)
    }

    @Test
    fun `every other failure stays on the form as an error`() {
        for (error in AuthError.entries - AuthError.DEVICE_NOT_ALLOWED) {
            var screensShown = 0
            val viewModel = RegisterViewModel(authenticationCallBacks = Failing(error))

            viewModel.register(navigateToDeviceNotAllowedScreen = { screensShown++ })

            assertEquals(error.name, 0, screensShown)
            assertEquals(error, viewModel.state.error)
            assertFalse(error.name, viewModel.state.isLoading)
        }
    }

    private fun RegisterViewModel.register(navigateToDeviceNotAllowedScreen: () -> Unit) {
        processRegistration(
            username = "ada4",
            password = "correct horse battery",
            navigateToPasswordLeakScreen = {},
            navigateToUserInformationScreen = { _, _, _ -> },
            navigateToDeviceNotAllowedScreen = navigateToDeviceNotAllowedScreen,
        )
    }

    /** A password no leak knows of, and a registration that fails with [error]. */
    private class Failing(private val error: AuthError) : AuthenticationCallBacks by TypingOnlyCallBacks() {
        override suspend fun passwordLeakAmount(password: String): AuthOutcome<Int> = AuthOutcome.Success(0)

        override suspend fun registration(username: String, password: String): AuthOutcome<String> =
            AuthOutcome.Failure(error)
    }
}
