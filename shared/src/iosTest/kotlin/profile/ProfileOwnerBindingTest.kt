package profile

import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.Result
import vault.InMemoryProfileKeyVault
import vault.unsealBound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"

/**
 * A profile opening as its owner's and nobody else's, whichever key opens it, with the server
 * stood in for by one that serves whatever it holds under whichever UUID it is asked for, and the
 * sealing done by the library itself.
 *
 * Alice and Bob each hold the key to the other's profile, as two members of a group whose creator
 * handed every member's key to the others do.
 */
class ProfileOwnerBindingTest {

    private val store = InMemoryProfileStore()

    private val aliceKey = generateSymmetricEncryptionKey()
    private val bobKey = generateSymmetricEncryptionKey()

    private val alice = ProfileRepository(
        profileStore = store,
        profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = aliceKey),
        userDao = UsersWritten(),
    )

    private val bob = ProfileRepository(
        profileStore = store,
        profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = bobKey),
        userDao = UsersWritten(),
    )

    @Test
    fun `a profile sealed for one user does not open as another's`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Martin"))
        assertIs<Result.Success<Unit>>(bob.uploadProfile(userUuid = BOB, username = "bob", fullName = "Bob Durand"))

        store.serve(userUuid = BOB, asUserUuid = ALICE)

        val read = bob.readFullName(userUuid = ALICE, profileEncryptionKey = bobKey)

        assertEquals(ProfileError.Crypto, assertIs<Result.Error<ProfileError>>(read).error)
    }

    @Test
    fun `a profile opens as its owner's`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Martin"))

        val read = bob.readFullName(userUuid = ALICE, profileEncryptionKey = aliceKey)

        assertEquals("Alice Martin", assertIs<Result.Success<FullNameRead>>(read).data.fullName)
    }

    /** Every field, the two the app has no screen for yet included. */
    @Test
    fun `every field is bound to its owner`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Martin"))

        val sealed = store.versionsOf(ALICE).getValue(profileEncryptionKeyPartialHash(aliceKey))

        listOf(
            "full_name" to sealed.fullName,
            "phone_number" to sealed.phoneNumber,
            "bank_details" to sealed.bankDetails,
        ).forEach { (field, content) ->
            assertNotNull(unsealBound(key = aliceKey, context = "profile:$ALICE:$field", content = content), field)
            assertNull(unsealBound(key = aliceKey, context = "profile:$BOB:$field", content = content), field)
            assertNull(unsealBound(key = aliceKey, context = "profile:$field", content = content), field)
        }
    }

    /**
     * Bob holds Alice's key, and seals a profile of his own under it. Served as hers, Alice's
     * rotation would otherwise seal his name under her new key, for every contact to read as hers.
     */
    @Test
    fun `a rotation refuses a profile somebody else sealed under the user's key`() = runBlocking<Unit> {
        val mallory = ProfileRepository(
            profileStore = store,
            profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = aliceKey),
            userDao = UsersWritten(),
        )

        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Martin"))
        assertIs<Result.Success<Unit>>(mallory.uploadProfile(userUuid = BOB, username = "bob", fullName = "Not Alice"))

        store.serve(userUuid = BOB, asUserUuid = ALICE)

        alice.oweProfileKeyRotation { }

        val rotation = alice.rotateProfileKey(userUuid = ALICE)

        assertEquals(ProfileError.Crypto, assertIs<Result.Error<ProfileError>>(rotation).error)
    }
}
