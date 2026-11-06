package app.lidory.duckcount.settings.viewmodel

import androidx.compose.runtime.snapshots.Snapshot
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.AccountDeletionError
import app.lidory.duckcount.settings.model.PasswordChangeError
import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What [ReserveDiscoveryUsernameViewModel.onUsernameChanged] makes of a username typed with
 * capitals, or with whitespace around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReserveDiscoveryUsernameViewModelTest {

    // The ViewModel starts its refresh on the main dispatcher as it is built, which a JVM test has
    // none of. Nothing it launches runs but what a test runs this dispatcher for.
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
     * Read straight after the edit, with no dispatcher run in between: the field takes its text
     * back in the same frame. As typed, so that the field never hands the keyboard a new text.
     */
    @Test
    fun `the username typed is held as typed right after the edit`() {
        val viewModel = ReserveDiscoveryUsernameViewModel(settingsCallBacks = NothingReservedCallBacks())

        viewModel.onUsernameChanged(username = "Ada4")

        assertEquals("Ada4", viewModel.draftUsername)
    }

    /** Lowercased, which the rules refuse a capital in. */
    @Test
    fun `the username typed is checked and reserved lowercased`() = runTest(mainDispatcher) {
        val viewModel = ReserveDiscoveryUsernameViewModel(settingsCallBacks = NothingReservedCallBacks())
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.onUsernameChanged(username = "Ada4")
        // What a recomposer does in the app, telling the screen's state that the draft changed.
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()

        val content = viewModel.state.value as DiscoveryUsernameScreenState.Content
        assertEquals("ada4", content.username)
        assertNull(content.inputError)
    }

    /**
     * Held as typed, so that a space typed after a word is not swallowed, and checked and reserved
     * without the whitespace around it, which the rules refuse as they refuse a space anywhere.
     */
    @Test
    fun `the username typed is checked and reserved without the whitespace around it`() =
        runTest(mainDispatcher) {
            val callBacks = NothingReservedCallBacks()
            val viewModel = ReserveDiscoveryUsernameViewModel(settingsCallBacks = callBacks)
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.onUsernameChanged(username = " Ada4 ")
            Snapshot.sendApplyNotifications()
            advanceUntilIdle()

            assertEquals(" Ada4 ", viewModel.draftUsername)
            val content = viewModel.state.value as DiscoveryUsernameScreenState.Content
            assertEquals("ada4", content.username)
            assertNull(content.inputError)

            viewModel.onReserveClicked()
            advanceUntilIdle()

            assertEquals("ada4", callBacks.reservedUsername)
        }
}

/**
 * A user with no username reserved yet, answering only what the screen asks while it is typed in,
 * a capital or whitespace around the username refused as the library refuses them, and taking the
 * reservation it is then asked for.
 */
private class NothingReservedCallBacks : SettingsCallBacks {
    /** The username [reserveDiscoveryUsername] was last asked to reserve. */
    var reservedUsername: String? = null
        private set

    override val maxUsernameLength = 32
    override val maxFullNameLength: Int get() = TODO()
    override val minimumPasswordStrength: Int get() = TODO()
    override val learnMoreLink: String get() = TODO()
    override val sourceCodeLink: String get() = TODO()
    override val termsOfUseLink: String get() = TODO()
    override val privacyPolicyLink: String get() = TODO()
    override val appVersionName: String get() = TODO()
    override val licences: List<String> get() = TODO()

    override suspend fun logOut(): Unit = TODO()
    override suspend fun deleteAccount(password: String): AccountDeletionError = TODO()
    override suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeError? = TODO()
    override fun getAuthenticatedUserUuid(): String? = TODO()
    override fun getUserUsername(): String? = TODO()
    override fun getUserFullName(uuid: String): Flow<String?> = TODO()
    override suspend fun refreshUserProfile(userUuid: String): Unit = TODO()
    override suspend fun updateUserProfile(userUuid: String, fullName: String): ProfileSaveError? = TODO()
    override fun getReservedUsername(): Flow<String?> = flowOf(null)
    override suspend fun refreshReservedDiscoveryUsername() = Unit
    override fun validateUsername(username: String): UsernameInputError? =
        if (username != username.lowercase() || username != username.trim()) {
            UsernameInputError.INVALID_CHARACTER
        } else {
            null
        }
    override fun normalizeUsername(username: String): String = TODO()
    override suspend fun reserveDiscoveryUsername(username: String): ReserveDiscoveryUsernameError? {
        reservedUsername = username
        return null
    }
}
