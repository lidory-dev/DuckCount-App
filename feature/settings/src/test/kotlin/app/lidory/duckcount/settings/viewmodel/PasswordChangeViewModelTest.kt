package app.lidory.duckcount.settings.viewmodel

import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.AccountDeletionError
import app.lidory.duckcount.settings.model.PasswordChangeFailure
import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private const val CURRENT = "correct horse battery staple"
private const val NEW = "tremble glacier marmalade oboe"

/** What [PasswordChangeViewModel] makes of a new password turning up in known breaches. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordChangeViewModelTest {

    // The change runs on the main dispatcher, which a JVM test has none of.
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * On a screen of its own, as at registration, rather than on the field: the form shows no
     * failure, and once the screen opened, the new password is forgotten, typed twice, for another
     * one to be chosen, the current one kept.
     */
    @Test
    fun `a leaked new password is told on a screen of its own`() = runTest(mainDispatcher) {
        val viewModel = PasswordChangeViewModel(settingsCallBacks = LeakingCallBacks(leakCount = 3))
        viewModel.onCurrentPasswordChanged(CURRENT)
        viewModel.onNewPasswordChanged(NEW)
        viewModel.onNewPasswordConfirmationChanged(NEW)

        viewModel.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(3, viewModel.state.leakAmount)
        assertNull(viewModel.state.error)
        assertFalse(viewModel.state.isChanging)
        assertFalse(viewModel.isChanged.value)

        viewModel.onLeakShown()

        assertNull(viewModel.state.leakAmount)
        assertEquals("", viewModel.state.newPassword)
        assertEquals("", viewModel.state.newPasswordConfirmation)
        assertEquals(CURRENT, viewModel.state.currentPassword)
        assertFalse(viewModel.state.canConfirm)
    }
}

/** A password change whose new password turns up in known breaches, [leakCount] times. */
private class LeakingCallBacks(private val leakCount: Int) : SettingsCallBacks {
    override val maxUsernameLength: Int get() = TODO()
    override val maxFullNameLength: Int get() = TODO()
    override val minimumPasswordStrength = 15
    override val learnMoreLink: String get() = TODO()
    override val sourceCodeLink: String get() = TODO()
    override val termsOfUseLink: String get() = TODO()
    override val privacyPolicyLink: String get() = TODO()
    override val appVersionName: String get() = TODO()
    override val licences: List<String> get() = TODO()

    override suspend fun logOut(): Unit = TODO()
    override suspend fun deleteAccount(password: String): AccountDeletionError = TODO()
    override suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeFailure =
        PasswordChangeFailure.Leaked(count = leakCount)
    override fun getAuthenticatedUserUuid(): String? = TODO()
    override fun getUserUsername(): String? = TODO()
    override fun getUserFullName(uuid: String): Flow<String?> = TODO()
    override suspend fun refreshUserProfile(userUuid: String): Unit = TODO()
    override suspend fun updateUserProfile(userUuid: String, fullName: String): ProfileSaveError? = TODO()
    override fun getReservedUsername(): Flow<String?> = TODO()
    override suspend fun refreshReservedDiscoveryUsername(): Unit = TODO()
    override fun validateUsername(username: String): UsernameInputError? = TODO()
    override fun normalizeUsername(username: String): String = TODO()
    override suspend fun reserveDiscoveryUsername(username: String): ReserveDiscoveryUsernameError? = TODO()
}
