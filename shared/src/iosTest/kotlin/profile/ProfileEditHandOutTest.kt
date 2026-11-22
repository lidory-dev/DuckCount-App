package profile

import app.lidory.duckcount.ProfileLimits
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.Result
import vault.InMemoryProfileKeyVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/**
 * What an edit of Alice's profile has done for her contacts and the members of her groups to read
 * it, which only the key to it handed over again tells them of: the key owed to everybody again
 * before the edit goes up, and a pass handing it out booked once it is saved, see
 * [ProfileRepository.uploadProfile].
 */
class ProfileEditHandOutTest {

    private val events = mutableListOf<String>()
    private val key = generateSymmetricEncryptionKey()
    private val store = InMemoryProfileStore(events = events)

    /** Alice's profiles, with somebody holding her key when [holdsKey]. */
    private fun profilesOf(holdsKey: Boolean = true) = ProfileRepository(
        profileStore = store,
        profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = key),
        userDao = UsersWritten(),
        oweProfileKeyAgain = {
            events += "owed"
            holdsKey
        },
        handOutSoon = { events += "hand-out booked" },
    )

    private suspend fun ProfileRepository.edit(fullName: String = "Alice Durand") =
        uploadProfile(userUuid = ALICE, username = "alice", fullName = fullName)

    /** Owed before: an upload whose answer was lost may have landed all the same. */
    @Test
    fun `an edit owes the key again before it goes up and books a hand-out once saved`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(profilesOf().edit())

        assertEquals(listOf("owed", "save ${profileEncryptionKeyPartialHash(key)}", "hand-out booked"), events)
    }

    /** At registration, say: no contact and no group yet. */
    @Test
    fun `an edit nobody holds the key to books nothing`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(profilesOf(holdsKey = false).edit())

        assertEquals(listOf("owed", "save ${profileEncryptionKeyPartialHash(key)}"), events)
    }

    /** The key stays owed, and the next sync hands it out, for an upload that may have landed. */
    @Test
    fun `an edit the server did not answer books nothing`() = runBlocking<Unit> {
        store.failsSaving = true

        assertIs<Result.Error<ProfileError>>(profilesOf().edit())

        assertEquals(listOf("owed", "save ${profileEncryptionKeyPartialHash(key)}"), events)
    }

    @Test
    fun `a name refused for its length owes nothing`() = runBlocking<Unit> {
        val tooLong = "a".repeat(ProfileLimits.MAX_FULL_NAME_LENGTH + 1)

        val upload = profilesOf().edit(fullName = tooLong)

        assertEquals(ProfileError.FullNameTooLong, assertIs<Result.Error<ProfileError>>(upload).error)
        assertEquals(emptyList(), events)
    }
}
