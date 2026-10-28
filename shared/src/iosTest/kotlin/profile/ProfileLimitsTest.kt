package profile

import app.lidory.duckcount.ProfileLimits
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.Result
import vault.InMemoryProfileKeyVault
import vault.sealBound
import vault.unsealBound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/**
 * The longest each field of a profile may be, see [ProfileLimits]: a full name past it refused
 * before anything is sealed, and the longest ciphertext a field at its limit seals to, which is
 * the cap the server holds that field to, with the sealing done by the library itself.
 */
class ProfileLimitsTest {

    private val store = InMemoryProfileStore()
    private val key = generateSymmetricEncryptionKey()
    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = key)
    private val users = UsersWritten()

    private val alice = ProfileRepository(profileStore = store, profileKeyVault = vault, userDao = users)

    @Test
    fun `a full name past the limit is refused before anything is sealed or sent`() = runBlocking<Unit> {
        val upload = alice.uploadProfile(
            userUuid = ALICE,
            username = "alice",
            fullName = "a".repeat(ProfileLimits.MAX_FULL_NAME_LENGTH + 1),
        )

        assertEquals(ProfileError.FullNameTooLong, assertIs<Result.Error<ProfileError>>(upload).error)
        assertEquals(0, vault.reads)
        assertTrue(store.saves.isEmpty())
        assertTrue(users.written.isEmpty())
    }

    /** Counted in UTF-16 units, as every text limit of the app is: an emoji is two. */
    @Test
    fun `a full name of two-unit characters past the limit is refused`() = runBlocking<Unit> {
        val upload = alice.uploadProfile(
            userUuid = ALICE,
            username = "alice",
            fullName = "😀".repeat(ProfileLimits.MAX_FULL_NAME_LENGTH / 2) + "a",
        )

        assertEquals(ProfileError.FullNameTooLong, assertIs<Result.Error<ProfileError>>(upload).error)
    }

    @Test
    fun `a full name at the limit is sealed and saved`() = runBlocking<Unit> {
        val fullName = "€".repeat(ProfileLimits.MAX_FULL_NAME_LENGTH)

        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = fullName))

        val sealed = store.versionsOf(ALICE).getValue(profileEncryptionKeyPartialHash(key)).fullName

        assertEquals(fullName, unsealBound(key = key, context = "profile:$ALICE:full_name", content = sealed)?.decodeToString())
        assertEquals(224, sealed.ciphertext.length)
    }

    /**
     * Three bytes of UTF-8 a unit is the most there is, a pair of surrogates taking four for its
     * two, and the 16-byte tag is all AES-GCM adds: the server's caps, 224, 152 and 192 characters
     * of Base64, are these exactly.
     */
    @Test
    fun `a field at its limit seals to the longest ciphertext the server takes for it`() {
        listOf(
            Triple("full_name", ProfileLimits.MAX_FULL_NAME_LENGTH, 224),
            Triple("phone_number", ProfileLimits.MAX_PHONE_NUMBER_LENGTH, 152),
            Triple("bank_details", ProfileLimits.MAX_BANK_DETAILS_LENGTH, 192),
        ).forEach { (field, maxLength, cap) ->
            val widest = sealedLength(field = field, value = "€".repeat(maxLength))

            assertEquals(cap, widest, field)
            assertTrue(sealedLength(field = field, value = "😀".repeat(maxLength / 2)) <= cap, field)
            assertTrue(sealedLength(field = field, value = "a".repeat(maxLength)) <= cap, field)
        }
    }

    /** The Base64 length of [value] sealed as Alice's [field], as the profile goes up. */
    private fun sealedLength(field: String, value: String): Int =
        assertNotNull(
            sealBound(key = key, context = "profile:$ALICE:$field", plaintext = value.encodeToByteArray()),
        ).ciphertext.length
}
