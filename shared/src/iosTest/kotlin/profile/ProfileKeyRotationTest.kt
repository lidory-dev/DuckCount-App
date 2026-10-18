package profile

import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.InMemoryProfileKeyVault
import vault.unsealBound
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/** Each field of a profile and what it is sealed bound to, as `ProfileRepository` seals them. */
private val FIELDS: List<Pair<String, (UserProfileDto) -> EncryptedFieldDto>> = listOf(
    "profile:full_name" to { it.fullName },
    "profile:phone_number" to { it.phoneNumber },
    "profile:bank_details" to { it.bankDetails },
)

/**
 * Alice rotating the key to her profile, as removing a contact has her do, with the server and
 * the vault stood in for and the sealing done by the library itself.
 */
class ProfileKeyRotationTest {

    private val events = mutableListOf<String>()

    private val originalKey = generateSymmetricEncryptionKey()
    private val originalHash = profileEncryptionKeyPartialHash(originalKey)

    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = originalKey, events = events)
    private val store = InMemoryProfileStore(events = events)

    private val profiles = ProfileRepository(
        profileStore = store,
        profileKeyVault = vault,
        userDao = UsersWritten(),
    )

    /** Her profile as she filled it in, under the key she holds now. */
    private suspend fun upload(fullName: String = "Alice Martin") {
        assertIs<Result.Success<Unit>>(
            profiles.uploadProfile(userUuid = ALICE, username = "alice", fullName = fullName),
        )
    }

    /**
     * What a removal of hers owes the key, see `ContactRepository.remove`, the removal logged as
     * it is written down.
     */
    private suspend fun owe() {
        profiles.oweProfileKeyRotation { events += "removal" }
    }

    /** A removal of hers, then the rotation it owes. */
    private suspend fun rotate(): EmptyResult<ProfileError> {
        owe()

        return profiles.rotateProfileKey(userUuid = ALICE)
    }

    /**
     * Kept on the device before the removal is written down, then uploaded first, the vault
     * written only once the server holds something under the new key. The removal first, a
     * process killed in between would leave it waiting for a rotation with no key owed, which the
     * rotation takes for one done; the vault first, the new key could become the one every contact
     * is handed with nothing to read.
     */
    @Test
    fun `the profile goes up under the new key before the vault takes it`() = runBlocking<Unit> {
        upload()
        events.clear()

        owe()

        val newHash = profileEncryptionKeyPartialHash(assertNotNull(vault.pendingKey))

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))

        assertNotEquals(originalHash, newHash)
        assertEquals(newHash, profileEncryptionKeyPartialHash(vault.profileEncryptionKey))
        assertEquals(
            listOf(
                "pending $newHash",
                "removal",
                "vault read",
                "save $newHash",
                "vault replace $newHash",
                "pending dropped",
            ),
            events,
        )
        assertNull(vault.pendingKey)
    }

    /** Every field, the two the app has no screen for yet included, bound as it was. */
    @Test
    fun `the new key reads the very profile the old one did`() = runBlocking<Unit> {
        upload()

        rotate()

        val newKey = vault.profileEncryptionKey
        val before = store.versionsOf(ALICE).getValue(originalHash)
        val after = store.versionsOf(ALICE).getValue(profileEncryptionKeyPartialHash(newKey))

        FIELDS.forEach { (context, field) ->
            assertContentEquals(
                assertNotNull(unsealBound(key = originalKey, context = context, content = field(before))),
                assertNotNull(unsealBound(key = newKey, context = context, content = field(after))),
                context,
            )
        }

        assertEquals("Alice Martin", nameUnder(key = newKey))
    }

    /** What the contact just removed still reads, as it stood when they were removed. */
    @Test
    fun `the version under the old key stays as it was`() = runBlocking<Unit> {
        upload()
        val before = store.versionsOf(ALICE).getValue(originalHash)

        rotate()
        upload(fullName = "Alice Durand")

        assertEquals(before, store.versionsOf(ALICE).getValue(originalHash))
        assertEquals("Alice Martin", nameUnder(key = originalKey))
        assertEquals("Alice Durand", nameUnder(key = vault.profileEncryptionKey))
    }

    /** No rotation owed, no rotation: the key stays, and nothing goes up. */
    @Test
    fun `a rotation with nothing owed changes nothing`() = runBlocking<Unit> {
        upload()
        events.clear()

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))

        assertContentEquals(originalKey, vault.profileEncryptionKey)
        assertEquals(listOf("vault read"), events)
    }

    @Test
    fun `an upload that fails leaves the vault on the old key`() = runBlocking<Unit> {
        upload()
        store.failsSaving = true

        val rotation = rotate()

        assertEquals(ProfileError.Network(NetworkError.REQUEST_TIMEOUT), assertIs<Result.Error<ProfileError>>(rotation).error)
        assertContentEquals(originalKey, vault.profileEncryptionKey)
        assertTrue(events.none { it.startsWith("vault replace") })
    }

    /**
     * A fresh key each attempt would leave a version under each one whose vault write failed,
     * and enough of them would prune the version the vault's key opens.
     */
    @Test
    fun `a vault write that fails leaves the old key and the next attempt uploads under the same new one`() =
        runBlocking<Unit> {
            upload()
            vault.failsReplacement = true

            val failed = rotate()

            assertIs<ProfileError.Vault>(assertIs<Result.Error<ProfileError>>(failed).error)
            assertContentEquals(originalKey, vault.profileEncryptionKey)

            val pendingHash = profileEncryptionKeyPartialHash(assertNotNull(vault.pendingKey))

            vault.failsReplacement = false

            assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))

            val newHash = profileEncryptionKeyPartialHash(vault.profileEncryptionKey)

            assertEquals(pendingHash, newHash)
            assertEquals(listOf(originalHash, newHash, newHash), store.saves)
            assertEquals(setOf(originalHash, newHash), store.versionsOf(ALICE).keys)
            assertNull(vault.pendingKey)
            assertEquals("Alice Martin", nameUnder(key = vault.profileEncryptionKey))
        }

    /** As many attempts as fail, one version added, whatever the server prunes beyond ten. */
    @Test
    fun `failed attempts add one version between them`() = runBlocking<Unit> {
        upload()
        vault.failsReplacement = true

        owe()
        repeat(times = 12) { profiles.rotateProfileKey(userUuid = ALICE) }

        assertEquals(2, store.versionsOf(ALICE).size)
        assertEquals("Alice Martin", nameUnder(key = originalKey))
    }

    /** An upload that never landed leaves the same key to go up under at the next attempt. */
    @Test
    fun `an upload that fails keeps the pending key for the next attempt`() = runBlocking<Unit> {
        upload()
        store.failsSaving = true

        rotate()

        val pending = assertNotNull(vault.pendingKey)

        store.failsSaving = false

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))
        assertContentEquals(pending, vault.profileEncryptionKey)
    }

    /**
     * The vault took the key and only its answer was lost: the next attempt finds it there, and
     * the rotation is done, with nothing uploaded again and no second key.
     */
    @Test
    fun `a lost answer completes without a second key`() = runBlocking<Unit> {
        upload()
        vault.losesReplacementAnswer = true

        val lost = rotate()

        assertIs<ProfileError.Vault>(assertIs<Result.Error<ProfileError>>(lost).error)

        val newKey = vault.profileEncryptionKey
        val savesBefore = store.saves.toList()

        assertNotEquals(originalHash, profileEncryptionKeyPartialHash(newKey))

        vault.losesReplacementAnswer = false

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))

        assertContentEquals(newKey, vault.profileEncryptionKey)
        assertEquals(savesBefore, store.saves)
        assertEquals(setOf(originalHash, profileEncryptionKeyPartialHash(newKey)), store.versionsOf(ALICE).keys)
        assertNull(vault.pendingKey)
    }

    /**
     * A removal made while a rotation is pending, see `ContactRepository.remove`: the key the vault
     * may already hold, and the contact just removed may hold too, is not taken for the rotation
     * that removal owes.
     */
    @Test
    fun `a removal while a rotation is pending owes a new key`() = runBlocking<Unit> {
        upload()
        vault.losesReplacementAnswer = true

        rotate()

        val landed = vault.profileEncryptionKey

        vault.losesReplacementAnswer = false
        owe()

        assertFalse(landed.contentEquals(assertNotNull(vault.pendingKey)))

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))

        assertFalse(landed.contentEquals(vault.profileEncryptionKey))
        assertEquals(3, store.versionsOf(ALICE).size)
        assertEquals("Alice Martin", nameUnder(key = vault.profileEncryptionKey))
    }

    /** What tells a contact holding the old key that a newer one exists, and nothing more. */
    @Test
    fun `the old key reads its version as no longer the latest`() = runBlocking<Unit> {
        upload()

        assertTrue(readUnder(key = originalKey).isLatest)

        rotate()

        assertFalse(readUnder(key = originalKey).isLatest)
        assertTrue(readUnder(key = vault.profileEncryptionKey).isLatest)
    }

    /** An edit under the current key rewrites its version, which is no newer for it. */
    @Test
    fun `an edit under the current key leaves the older version not the latest`() = runBlocking<Unit> {
        upload()
        rotate()
        upload(fullName = "Alice Durand")

        assertFalse(readUnder(key = originalKey).isLatest)
        assertTrue(readUnder(key = vault.profileEncryptionKey).isLatest)
    }

    /** One she never stored, or the server has pruned since: nothing is sealed, nothing replaced. */
    @Test
    fun `a rotation with no profile to read under the current key changes nothing`() = runBlocking<Unit> {
        val rotation = rotate()

        assertEquals(ProfileError.NotStored, assertIs<Result.Error<ProfileError>>(rotation).error)
        assertContentEquals(originalKey, vault.profileEncryptionKey)
        assertTrue(store.versionsOf(ALICE).isEmpty())
        assertNotNull(vault.pendingKey)
    }

    /**
     * Sealed under the old key and saved once the rotation was done, the edit would rewrite the
     * version the removed contact still reads.
     */
    @Test
    fun `an edit made while the key rotates waits for it and goes up under the new key`() = runBlocking<Unit> {
        upload()
        owe()

        val held = store.holdNextSave()
        val rotation = async { profiles.rotateProfileKey(userUuid = ALICE) }

        held.entered.await()

        val readsBefore = vault.reads
        val edit = async { profiles.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Durand") }

        repeat(times = 5) { yield() }

        // Not even the key read yet: it waits for the rotation as a whole.
        assertEquals(readsBefore, vault.reads)

        held.release.complete(Unit)

        assertIs<Result.Success<Unit>>(rotation.await())
        assertIs<Result.Success<Unit>>(edit.await())

        val newHash = profileEncryptionKeyPartialHash(vault.profileEncryptionKey)

        assertEquals(listOf(originalHash, newHash, newHash), store.saves)
        assertEquals("Alice Martin", nameUnder(key = originalKey))
        assertEquals("Alice Durand", nameUnder(key = vault.profileEncryptionKey))
    }

    /**
     * The rotation a removal owes has yet to run, a failure having put it off: the contact removed
     * still holds the key the vault does, and the edit goes up under the new one instead, the
     * rotation itself. The version they read stays as it stood when they were removed.
     */
    @Test
    fun `an edit made while a rotation is owed is the rotation under the new key`() = runBlocking<Unit> {
        upload()
        owe()

        val owedKey = assertNotNull(vault.pendingKey)
        val owedHash = profileEncryptionKeyPartialHash(owedKey)
        events.clear()

        upload(fullName = "Alice Durand")

        assertEquals(listOf("vault read", "save $owedHash", "vault replace $owedHash", "pending dropped"), events)
        assertContentEquals(owedKey, vault.profileEncryptionKey)
        assertNull(vault.pendingKey)
        assertEquals("Alice Martin", nameUnder(key = originalKey))
        assertEquals("Alice Durand", nameUnder(key = owedKey))
        assertTrue(readUnder(key = owedKey).isLatest)

        // Nothing left for the rotation the removal waits for.
        events.clear()

        assertIs<Result.Success<Unit>>(profiles.rotateProfileKey(userUuid = ALICE))
        assertEquals(listOf("vault read"), events)
        assertEquals(setOf(originalHash, owedHash), store.versionsOf(ALICE).keys)
    }

    /**
     * Its upload landed and its vault write did not, so the vault still holds the old key: the
     * edit goes up under the same new key, and adds no version.
     */
    @Test
    fun `an edit after a rotation whose vault write failed goes up under the same new key`() = runBlocking<Unit> {
        upload()
        vault.failsReplacement = true

        rotate()

        val owedKey = assertNotNull(vault.pendingKey)

        vault.failsReplacement = false

        upload(fullName = "Alice Durand")

        assertContentEquals(owedKey, vault.profileEncryptionKey)
        assertEquals(setOf(originalHash, profileEncryptionKeyPartialHash(owedKey)), store.versionsOf(ALICE).keys)
        assertEquals("Alice Martin", nameUnder(key = originalKey))
        assertEquals("Alice Durand", nameUnder(key = owedKey))
    }

    /**
     * Rather than sealed under the key the contact removed holds. Not even tried under it, as the
     * saves show: with every save failing, the version under that key would look the same either
     * way.
     */
    @Test
    fun `an edit whose rotation fails is refused and leaves the version under the old key as it was`() =
        runBlocking<Unit> {
            upload()
            owe()
            val owedHash = profileEncryptionKeyPartialHash(assertNotNull(vault.pendingKey))
            val before = store.versionsOf(ALICE).getValue(originalHash)
            store.failsSaving = true

            val edit = profiles.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Durand")

            assertEquals(ProfileError.Network(NetworkError.REQUEST_TIMEOUT), assertIs<Result.Error<ProfileError>>(edit).error)
            assertEquals(listOf(originalHash, owedHash), store.saves)
            assertEquals(before, store.versionsOf(ALICE).getValue(originalHash))
            assertContentEquals(originalKey, vault.profileEncryptionKey)
            assertNotNull(vault.pendingKey)
        }

    /**
     * A pending key the vault already holds, its drop never having reached the disk before the
     * process ended: the edit goes up under the current key, the very same one, and drops it,
     * with nothing rotated.
     */
    @Test
    fun `an edit drops a pending key the vault already holds and rotates nothing`() = runBlocking<Unit> {
        upload()
        vault.pendingKey = originalKey.copyOf()
        events.clear()

        upload(fullName = "Alice Durand")

        assertEquals(listOf("vault read", "pending dropped", "save $originalHash"), events)
        assertContentEquals(originalKey, vault.profileEncryptionKey)
        assertNull(vault.pendingKey)
        assertEquals(setOf(originalHash), store.versionsOf(ALICE).keys)
    }

    /**
     * The rotation has no profile to read under the current key, never stored or pruned since,
     * and would fail for good. An edit brings its own.
     */
    @Test
    fun `an edit completes a rotation that has nothing to read`() = runBlocking<Unit> {
        owe()
        val owedKey = assertNotNull(vault.pendingKey)

        assertEquals(ProfileError.NotStored, assertIs<Result.Error<ProfileError>>(profiles.rotateProfileKey(userUuid = ALICE)).error)

        upload()

        assertContentEquals(owedKey, vault.profileEncryptionKey)
        assertEquals("Alice Martin", nameUnder(key = owedKey))
        assertNull(vault.pendingKey)
    }

    /** The edit waits for the removal, and goes up under the key it owes, never under the old one. */
    @Test
    fun `an edit made while a removal is being written down waits for it`() = runBlocking<Unit> {
        upload()

        val writing = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val removal = async {
            profiles.oweProfileKeyRotation {
                writing.complete(Unit)
                written.await()
            }
        }

        writing.await()

        val edit = async { profiles.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Durand") }

        repeat(times = 5) { yield() }

        assertEquals(listOf(originalHash), store.saves)

        written.complete(Unit)
        removal.await()

        assertIs<Result.Success<Unit>>(edit.await())
        assertEquals("Alice Martin", nameUnder(key = originalKey))
        assertEquals("Alice Durand", nameUnder(key = vault.profileEncryptionKey))
    }

    private suspend fun nameUnder(key: ByteArray): String =
        assertIs<Result.Success<String>>(profiles.getFullName(userUuid = ALICE, profileEncryptionKey = key)).data

    private suspend fun readUnder(key: ByteArray): FullNameRead =
        assertIs<Result.Success<FullNameRead>>(profiles.readFullName(userUuid = ALICE, profileEncryptionKey = key)).data
}
