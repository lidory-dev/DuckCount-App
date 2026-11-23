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
import kotlin.test.assertNull

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/** When Alice saves her profile, in milliseconds since the epoch. */
private const val SAVED_AT = 1_763_900_000_000L

/**
 * What an edit of Alice's profile has done for her contacts and the members of her groups to read
 * it, which only the key to it handed over again tells them of: the edit recorded, the key owed to
 * everybody again before the edit goes up, and a pass handing it out booked once it is saved, see
 * [ProfileRepository.uploadProfile].
 */
class ProfileEditHandOutTest {

    private val events = mutableListOf<String>()
    private val key = generateSymmetricEncryptionKey()
    private val store = InMemoryProfileStore(events = events)
    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = key)

    /** The time on Alice's device. */
    private var now = SAVED_AT

    /** Alice's profiles, with somebody holding her key when [holdsKey]. */
    private fun profilesOf(holdsKey: Boolean = true) = ProfileRepository(
        profileStore = store,
        profileKeyVault = vault,
        userDao = UsersWritten(),
        oweProfileKeyAgain = {
            events += "owed"
            holdsKey
        },
        handOutSoon = { events += "hand-out booked" },
        now = { now },
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

    /** Which every post of her key into a group carries, see GroupProfileKeyDelivery. */
    @Test
    fun `an edit is recorded at the time it was saved`() = runBlocking<Unit> {
        profilesOf().edit()

        assertEquals(SAVED_AT, vault.profileRevision)
    }

    /** Her device's clock gone back since, or two edits in one millisecond: still a later edit. */
    @Test
    fun `an edit is recorded later than the one before whatever the clock says`() = runBlocking<Unit> {
        val profiles = profilesOf()

        profiles.edit()
        now = SAVED_AT - 60_000
        profiles.edit(fullName = "Alice Roux")

        assertEquals(SAVED_AT + 1, vault.profileRevision)
    }

    @Test
    fun `a name refused for its length owes nothing`() = runBlocking<Unit> {
        val tooLong = "a".repeat(ProfileLimits.MAX_FULL_NAME_LENGTH + 1)

        val upload = profilesOf().edit(fullName = tooLong)

        assertEquals(ProfileError.FullNameTooLong, assertIs<Result.Error<ProfileError>>(upload).error)
        assertEquals(emptyList(), events)
        assertNull(vault.profileRevision)
    }
}
