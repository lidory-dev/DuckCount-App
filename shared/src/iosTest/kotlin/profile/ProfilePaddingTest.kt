package profile

import app.lidory.duckcount.ProfileLimits
import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.Result
import vault.InMemoryProfileKeyVault
import vault.sealBound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/** What the server's caps come to, see [ProfileLimits], which every field now seals to exactly. */
private const val FULL_NAME_LENGTH = 224
private const val PHONE_NUMBER_LENGTH = 152
private const val BANK_DETAILS_LENGTH = 192

/**
 * Every field of a profile padded to one size, for its length to tell the server nothing: how long
 * a name is, or whether a phone number or bank details were given. With the server and the vault
 * stood in for and the sealing done by the library itself.
 */
class ProfilePaddingTest {

    private val store = InMemoryProfileStore()
    private val key = generateSymmetricEncryptionKey()
    private val hash = profileEncryptionKeyPartialHash(key)
    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = key)

    private val alice = ProfileRepository(profileStore = store, profileKeyVault = vault, userDao = UsersWritten())

    private suspend fun upload(fullName: String = "Ada Lovelace") {
        assertIs<Result.Success<Unit>>(alice.uploadProfile(userUuid = ALICE, username = "alice", fullName = fullName))
    }

    /** [text] sealed as Alice's [field], as a client of its own would. */
    private fun sealedAs(field: String, text: String): EncryptedFieldDto =
        assertNotNull(sealBound(key = key, context = "profile:$ALICE:$field", plaintext = text.encodeToByteArray()))

    /** Alice's profile on the server with [change] made to it. */
    private suspend fun storedWith(change: (UserProfileDto) -> UserProfileDto) {
        val current = store.versionsOf(ALICE).getValue(hash)

        assertIs<Result.Success<*>>(
            store.saveUserProfile(userUuid = ALICE, encryptionKeyPartialHash = hash, body = change(current)),
        )
    }

    private fun assertLengths(version: UserProfileDto, name: String) {
        assertEquals(FULL_NAME_LENGTH, version.fullName.ciphertext.length, name)
        assertEquals(PHONE_NUMBER_LENGTH, version.phoneNumber.ciphertext.length, name)
        assertEquals(BANK_DETAILS_LENGTH, version.bankDetails.ciphertext.length, name)
    }

    /** Sealing hides what a name says but not how long it is, which would say something of it as well. */
    @Test
    fun `every field seals to one length whatever the name says`() = runBlocking<Unit> {
        val most = ProfileLimits.MAX_FULL_NAME_LENGTH

        val names = listOf(
            "",
            "a",
            "Ada Lovelace",
            "a".repeat(most),
            // Two bytes each in UTF-8, three, and four for two characters.
            "é".repeat(most),
            "€".repeat(most),
            "😀".repeat(most / 2),
        )

        names.forEach { name ->
            upload(fullName = name)

            assertLengths(version = store.versionsOf(ALICE).getValue(hash), name = name)

            val read = alice.readFullName(userUuid = ALICE, profileEncryptionKey = key)

            assertEquals(name, assertIs<Result.Success<FullNameRead>>(read, name).data.fullName, name)
        }
    }

    /** It goes up anew under the new key, from what the old version opened to, and is padded all the same. */
    @Test
    fun `a rotated profile seals to the same lengths and reads the same`() = runBlocking<Unit> {
        upload()

        alice.oweProfileKeyRotation { }
        assertIs<Result.Success<Unit>>(alice.rotateProfileKey(userUuid = ALICE))

        val versions = store.versionsOf(ALICE)
        assertEquals(2, versions.size)
        versions.values.forEach { assertLengths(version = it, name = "a version") }

        val newKey = assertIs<Result.Success<ByteArray>>(vault.getOrCreateProfileEncryptionKey()).data
        val read = alice.readFullName(userUuid = ALICE, profileEncryptionKey = newKey)

        assertEquals("Ada Lovelace", assertIs<Result.Success<FullNameRead>>(read).data.fullName)
    }

    /** As a client of its own would seal it: the padding alone is off, under the right key and context. */
    @Test
    fun `a full name not padded to the one size is refused`() = runBlocking<Unit> {
        upload()

        val size = ProfileLimits.MAX_FULL_NAME_LENGTH * 3

        suspend fun readingWith(fullName: String): Result<FullNameRead, ProfileError> {
            storedWith { it.copy(fullName = sealedAs("full_name", fullName)) }

            return alice.readFullName(userUuid = ALICE, profileEncryptionKey = key)
        }

        // Padded as it should be, as a control.
        assertEquals("Ada", assertIs<Result.Success<FullNameRead>>(readingWith("Ada".padEnd(size))).data.fullName)

        listOf("Ada", "Ada".padEnd(size - 1), "Ada".padEnd(size + 1)).forEach { fullName ->
            assertEquals(
                ProfileError.Crypto,
                assertIs<Result.Error<ProfileError>>(readingWith(fullName), "${fullName.length} characters").error,
            )
        }
    }

    /**
     * The one way either is read as yet: a rotation takes every field across to the new key. Each from
     * a profile of its own, putting one right taking the rotation it owed.
     */
    @Test
    fun `a phone number or bank details not padded to the one size are refused`() = runBlocking<Unit> {
        val fields = listOf(
            "phone_number" to 0,
            "phone_number" to ProfileLimits.MAX_PHONE_NUMBER_LENGTH * 3 + 1,
            "bank_details" to 0,
            "bank_details" to ProfileLimits.MAX_BANK_DETAILS_LENGTH * 3 - 1,
        )

        fields.forEach { (field, size) ->
            val store = InMemoryProfileStore()
            val key = generateSymmetricEncryptionKey()
            val hash = profileEncryptionKeyPartialHash(key)
            val owner = ProfileRepository(
                profileStore = store,
                profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = key),
                userDao = UsersWritten(),
            )

            assertIs<Result.Success<Unit>>(owner.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Ada"))
            owner.oweProfileKeyRotation { }

            val sealed = assertNotNull(
                sealBound(key = key, context = "profile:$ALICE:$field", plaintext = "".padEnd(size).encodeToByteArray()),
            )
            val current = store.versionsOf(ALICE).getValue(hash)

            store.saveUserProfile(
                userUuid = ALICE,
                encryptionKeyPartialHash = hash,
                body = if (field == "phone_number") current.copy(phoneNumber = sealed) else current.copy(bankDetails = sealed),
            )

            val rotation = owner.rotateProfileKey(userUuid = ALICE)

            assertEquals(ProfileError.Crypto, assertIs<Result.Error<ProfileError>>(rotation, "$field of $size").error, "$field of $size")
        }
    }

    /** Whoever holds a key that opens nothing readable is told so, and no key is at fault. */
    @Test
    fun `a profile saved anew reads again after one refused for its padding`() = runBlocking<Unit> {
        upload()

        storedWith { it.copy(fullName = sealedAs("full_name", "Ada")) }
        assertIs<Result.Error<ProfileError>>(alice.readFullName(userUuid = ALICE, profileEncryptionKey = key))

        upload(fullName = "Ada Lovelace")

        val read = alice.readFullName(userUuid = ALICE, profileEncryptionKey = key)

        assertEquals("Ada Lovelace", assertIs<Result.Success<FullNameRead>>(read).data.fullName)
    }
}
