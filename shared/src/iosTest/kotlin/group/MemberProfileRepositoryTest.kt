package group

import database.data.local.dao.ContactTables
import database.data.local.dao.MemberProfileTables
import database.data.local.dao.copy
import database.data.local.entity.ContactEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.MemberProfileEntity
import kotlinx.coroutines.runBlocking
import profile.InMemoryProfileStore
import profile.ProfileError
import profile.ProfileRepository
import profile.UsersWritten
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.NetworkError
import util.Result
import vault.InMemoryMemberProfileVault
import vault.InMemoryProfileKeyVault
import vault.MemberProfileBook
import vault.VaultError
import vault.VaultMemberProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NINA = "44444444-4444-4444-4444-444444444444"
private const val PAUL = "66666666-6666-6666-6666-666666666666"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val CAROL = "55555555-5555-5555-5555-555555555555"

/** When the server stored the post each key from its owner comes in, in milliseconds since the epoch. */
private const val SENT_AT = 1_000L

/**
 * What the user's groups hand over of their members' profile keys comes to on this device: which
 * key is kept, the names read with them, and their copy in the vault, with the database stood in
 * for by [MemberProfileTables], the vault by [InMemoryMemberProfileVault] and the server by
 * [InMemoryProfileStore]: nothing here reaches the network.
 *
 * Nina and Paul are members of a group of the user's, neither of them a contact. Carol is a
 * contact. Each of them filled their profile in under a key of their own.
 */
/** How many times a backup writes the profile keys before it gives them up to another device, see `BACK_UP_ATTEMPTS`. */
private const val BACK_UP_ATTEMPTS_MADE = 3

class MemberProfileRepositoryTest {

    private val contactTables = ContactTables()
    private val tables = MemberProfileTables(contacts = contactTables)
    private val store = InMemoryProfileStore()
    private val vault = InMemoryMemberProfileVault()

    private val ninaKey = generateSymmetricEncryptionKey()
    private val ninaNewKey = generateSymmetricEncryptionKey()
    private val paulKey = generateSymmetricEncryptionKey()
    private val carolKey = generateSymmetricEncryptionKey()

    /** What the user's device reads anybody's profile with. */
    private val profiles = ProfileRepository(
        profileStore = store,
        profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = generateSymmetricEncryptionKey()),
        userDao = UsersWritten(),
    )

    /** Whose profile was read, in order. */
    private val reads = mutableListOf<String>()

    /** Runs as a profile is being read, before its answer comes back. */
    private var duringRead: suspend () -> Unit = {}

    private var toRestore = true

    /** The signature keys of the members of the groups this device holds, as the database lists them. */
    private var heldKeys = emptyList<GroupMemberSignatureKeyEntity>()

    /** The groups nobody is left to vouch for. */
    private var leftGroups = emptyList<String>()

    /** What the last backup of the groups' member keys found the vault holding. */
    private var backedUpDigest: Int? = null

    private val members = MemberProfileRepository(
        memberProfileDao = tables.memberProfileDao,
        memberProfileVault = vault,
        readFullName = { userUuid, key ->
            reads += userUuid
            duringRead()
            profiles.getFullName(userUuid = userUuid, profileEncryptionKey = key)
        },
        isToRestore = { toRestore },
        markRestored = { toRestore = false },
        heldMemberKeys = { heldKeys },
        groupsToForget = { leftGroups },
        backedUpMemberKeys = { backedUpDigest },
        markMemberKeysBackedUp = { backedUpDigest = it },
    )

    private val ninaSignatureKey = byteArrayOf(1, 1, 1)
    private val paulSignatureKey = byteArrayOf(2, 2, 2)

    private fun signatureKey(groupUuid: String, userUuid: String, key: ByteArray) =
        GroupMemberSignatureKeyEntity(groupUuid = groupUuid, userUuid = userUuid, signatureKey = key)

    /** A device coming back from a log-out takes a GroupInfo from any of them, see GroupRejoiner. */
    @Test
    fun `the signature keys of each group's members reach the vault`() = runBlocking<Unit> {
        heldKeys = listOf(
            signatureKey(GROUP, NINA, ninaSignatureKey),
            signatureKey(GROUP, PAUL, paulSignatureKey),
            signatureKey(OTHER_GROUP, PAUL, paulSignatureKey),
        )

        assertIs<Result.Success<Unit>>(members.backUp())

        val held = vault.book.memberKeysOf(groupUuid = GROUP)

        assertEquals(setOf(NINA, PAUL), held.keys)
        assertContentEquals(ninaSignatureKey, held[NINA])
        assertEquals(setOf(PAUL), vault.book.memberKeysOf(groupUuid = OTHER_GROUP).keys)
    }

    /** Somebody removed from a group vouches for it no more, once this device has read the removal. */
    @Test
    fun `a member no longer in a group goes from what the vault holds of it`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey), signatureKey(GROUP, PAUL, paulSignatureKey))
        members.backUp()

        heldKeys = listOf(signatureKey(GROUP, PAUL, paulSignatureKey))
        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(setOf(PAUL), vault.book.memberKeysOf(groupUuid = GROUP).keys)
    }

    @Test
    fun `a group the user is no longer in goes from the vault with its members`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey), signatureKey(OTHER_GROUP, PAUL, paulSignatureKey))
        members.backUp()

        heldKeys = listOf(signatureKey(OTHER_GROUP, PAUL, paulSignatureKey))
        leftGroups = listOf(GROUP)
        assertIs<Result.Success<Unit>>(members.backUp())

        assertTrue(vault.book.memberKeysOf(groupUuid = GROUP).isEmpty())
        assertEquals(setOf(PAUL), vault.book.memberKeysOf(groupUuid = OTHER_GROUP).keys)
    }

    /**
     * Brought back after a log-out, a group has no tree on this device, so the database lists
     * nobody for it: what the vault holds is all there is to rejoin it from.
     */
    @Test
    fun `a group this device holds no tree of keeps what the vault holds of it`() = runBlocking<Unit> {
        vault.book = vault.book.withMemberKeys(changed = mapOf(GROUP to mapOf(NINA to ninaSignatureKey)), forgotten = emptySet())

        heldKeys = listOf(signatureKey(OTHER_GROUP, PAUL, paulSignatureKey))
        assertIs<Result.Success<Unit>>(members.backUp())

        assertContentEquals(ninaSignatureKey, vault.book.memberKeysOf(groupUuid = GROUP)[NINA])
        assertEquals(setOf(PAUL), vault.book.memberKeysOf(groupUuid = OTHER_GROUP).keys)
    }

    @Test
    fun `a sync that changed no member asks the vault nothing whatever order the keys come in`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey), signatureKey(GROUP, PAUL, paulSignatureKey))
        members.backUp()

        heldKeys = heldKeys.reversed()
        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(1, vault.memberKeyUpdates)
        assertEquals(1, vault.writes)
    }

    /** A log-in forgets what the last backup found, and the vault holding it all already takes no write. */
    @Test
    fun `what the vault holds already is not written again`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey))
        members.backUp()

        backedUpDigest = null
        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(2, vault.memberKeyUpdates)
        assertEquals(1, vault.writes)
    }

    @Test
    fun `a vault out of reach is asked again by the next backup`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey))
        vault.failsWriting = true

        assertIs<Result.Error<GroupSyncError>>(members.backUp())
        assertNull(backedUpDigest)

        vault.failsWriting = false

        assertIs<Result.Success<Unit>>(members.backUp())
        assertEquals(setOf(NINA), vault.book.memberKeysOf(groupUuid = GROUP).keys)
    }

    @Test
    fun `another device writing in between is read and merged again`() = runBlocking<Unit> {
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey))
        vault.racingWrites += mapOf(PAUL to VaultMemberProfile(profileEncryptionKey = paulKey, isFromOwner = true, fullName = null))

        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(setOf(NINA), vault.book.memberKeysOf(groupUuid = GROUP).keys)
        assertEquals(setOf(PAUL), vault.book.members.keys)
    }

    /** Neither waits on the other. */
    @Test
    fun `the keys of the groups' members go up though the profile keys could not`() = runBlocking<Unit> {
        tables.memberProfileDao.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)
        heldKeys = listOf(signatureKey(GROUP, NINA, ninaSignatureKey))
        var writesFailed = 0

        vault.duringWrite = {
            if (writesFailed++ < BACK_UP_ATTEMPTS_MADE) {
                vault.racingWrites += mapOf(PAUL to VaultMemberProfile(profileEncryptionKey = paulKey, isFromOwner = true, fullName = null))
            }
        }

        assertIs<Result.Error<GroupSyncError>>(members.backUp())

        assertEquals(setOf(NINA), vault.book.memberKeysOf(groupUuid = GROUP).keys)
    }

    /** [userUuid]'s profile, filled in under [key]. */
    private suspend fun upload(userUuid: String, key: ByteArray, fullName: String) {
        val theirs = ProfileRepository(
            profileStore = store,
            profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = key),
            userDao = UsersWritten(),
        )

        assertIs<Result.Success<Unit>>(theirs.uploadProfile(userUuid = userUuid, username = "", fullName = fullName))
    }

    private fun row(userUuid: String) = tables.rows.getValue(userUuid)

    @Test
    fun `a relayed key fills in for a member nothing is held for`() = runBlocking<Unit> {
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)

        val nina = row(NINA)

        assertContentEquals(ninaKey, nina.profileEncryptionKey)
        assertFalse(nina.isFromOwner)
        assertFalse(nina.isBackedUp)
        assertContentEquals(ninaKey, members.keyFor(userUuid = NINA))
        assertNull(members.keyFor(userUuid = PAUL))
    }

    /** The creator may hold a key its owner replaced since, and a relayed key is as good as another. */
    @Test
    fun `a relayed key never replaces one held`() = runBlocking<Unit> {
        members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaNewKey, sentAt = SENT_AT)
        members.fillGap(userUuid = PAUL, profileEncryptionKey = paulKey)

        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)
        members.fillGap(userUuid = PAUL, profileEncryptionKey = generateSymmetricEncryptionKey())

        assertContentEquals(ninaNewKey, row(NINA).profileEncryptionKey)
        assertTrue(row(NINA).isFromOwner)
        assertContentEquals(paulKey, row(PAUL).profileEncryptionKey)
    }

    @Test
    fun `a key from its owner replaces a relayed one and keeps the name shown`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey, fullName = "Nina Leroy", isBackedUp = true))

        members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaNewKey, sentAt = SENT_AT)

        val nina = row(NINA)

        assertContentEquals(ninaNewKey, nina.profileEncryptionKey)
        assertTrue(nina.isFromOwner)
        assertEquals("Nina Leroy", nina.fullName)
        assertFalse(nina.isBackedUp)
    }

    /** A member posts their key into every group they are in. */
    @Test
    fun `the very key from its owner again changes nothing`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey, isFromOwner = true, isBackedUp = true))

        members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaKey.copyOf(), sentAt = SENT_AT)

        assertTrue(row(NINA).isBackedUp)
    }

    @Test
    fun `a member's name is read with the key held and saved`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Petit")
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)

        assertIs<Result.Success<Unit>>(members.readNames())

        val nina = row(NINA)

        assertEquals("Nina Petit", nina.fullName)
        assertEquals(profileEncryptionKeyPartialHash(ninaKey), nina.nameKeyHash)
        assertFalse(nina.isBackedUp)
    }

    /** A name is read once per key. */
    @Test
    fun `a member read under the key held is not read again`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Petit")
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)

        members.readNames()
        members.readNames()

        assertEquals(listOf(NINA), reads)
    }

    @Test
    fun `a new key from its owner has the member read again`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Petit")
        upload(NINA, key = ninaNewKey, fullName = "Nina Roux")
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)
        members.readNames()

        members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaNewKey, sentAt = SENT_AT)
        members.readNames()

        assertEquals(listOf(NINA, NINA), reads)
        assertEquals("Nina Roux", row(NINA).fullName)
    }

    /** Her account deleted, or her key rotated ten times since. */
    @Test
    fun `a key that opens nothing settles the read and keeps the older name`() = runBlocking<Unit> {
        tables.add(
            member(
                NINA,
                key = ninaNewKey,
                isFromOwner = true,
                fullName = "Nina Petit",
                nameKeyHash = profileEncryptionKeyPartialHash(ninaKey),
                isBackedUp = true,
            ),
        )

        assertIs<Result.Success<Unit>>(members.readNames())

        val nina = row(NINA)

        assertEquals("Nina Petit", nina.fullName)
        assertEquals(profileEncryptionKeyPartialHash(ninaNewKey), nina.nameKeyHash)
        assertTrue(nina.isBackedUp)

        members.readNames()

        assertEquals(listOf(NINA), reads)
    }

    @Test
    fun `a name read with a key replaced meanwhile is not saved`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Petit")
        upload(NINA, key = ninaNewKey, fullName = "Nina Roux")
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)

        duringRead = {
            duringRead = {}
            members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaNewKey, sentAt = SENT_AT)
        }

        members.readNames()

        assertNull(row(NINA).fullName)
        assertNull(row(NINA).nameKeyHash)

        members.readNames()

        assertEquals("Nina Roux", row(NINA).fullName)
        assertEquals(profileEncryptionKeyPartialHash(ninaNewKey), row(NINA).nameKeyHash)
    }

    /** The contact sync reads hers, with the key she handed over herself. */
    @Test
    fun `a contact's profile is not read here`() = runBlocking<Unit> {
        upload(CAROL, key = carolKey, fullName = "Carol Blanc")
        contactTables.add(contact(CAROL, key = carolKey))
        members.fillGap(userUuid = CAROL, profileEncryptionKey = carolKey)

        members.readNames()

        assertEquals(emptyList(), reads)
        assertNull(row(CAROL).fullName)
    }

    @Test
    fun `a former contact's profile is read here`() = runBlocking<Unit> {
        upload(CAROL, key = carolKey, fullName = "Carol Blanc")
        contactTables.add(contact(CAROL, key = carolKey).copy(isRemoved = true))
        members.fillGap(userUuid = CAROL, profileEncryptionKey = carolKey)

        members.readNames()

        assertEquals("Carol Blanc", row(CAROL).fullName)
    }

    /** Paul's profile, sealed for him, served as Nina's with his key relayed as hers. */
    @Test
    fun `a profile served as somebody else's is not taken`() = runBlocking<Unit> {
        upload(PAUL, key = paulKey, fullName = "Paul Roux")
        store.serve(userUuid = PAUL, asUserUuid = NINA)
        members.fillGap(userUuid = NINA, profileEncryptionKey = paulKey)

        assertIs<Result.Success<Unit>>(members.readNames())

        assertNull(row(NINA).fullName)
        assertNull(row(NINA).nameKeyHash)
    }

    @Test
    fun `a profile that could not be asked for is reported and records nothing`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Petit")
        upload(PAUL, key = paulKey, fullName = "Paul Roux")
        members.fillGap(userUuid = NINA, profileEncryptionKey = ninaKey)
        members.fillGap(userUuid = PAUL, profileEncryptionKey = paulKey)
        store.failsReading = true

        val read = members.readNames()

        assertEquals(
            GroupSyncError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<GroupSyncError>>(read).error,
        )
        assertEquals(listOf(NINA, PAUL), reads)
        assertNull(row(NINA).nameKeyHash)
        assertNull(row(PAUL).nameKeyHash)
    }

    @Test
    fun `a backup writes every member the vault does not hold and marks them`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey, isFromOwner = true, fullName = "Nina Petit"))
        tables.add(member(PAUL, key = paulKey))

        assertIs<Result.Success<Unit>>(members.backUp())

        val held = vault.book.members

        assertEquals(setOf(NINA, PAUL), held.keys)
        assertContentEquals(ninaKey, held.getValue(NINA).profileEncryptionKey)
        assertTrue(held.getValue(NINA).isFromOwner)
        assertEquals("Nina Petit", held.getValue(NINA).fullName)
        assertFalse(held.getValue(PAUL).isFromOwner)
        assertTrue(row(NINA).isBackedUp)
        assertTrue(row(PAUL).isBackedUp)
    }

    @Test
    fun `a backup with nothing to write writes nothing`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey, isBackedUp = true))

        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(0, vault.writes)
    }

    /** Another device of the user's met Nina's own post. */
    @Test
    fun `a relayed key never replaces one from its owner in the vault`() = runBlocking<Unit> {
        vault.book = MemberProfileBook.EMPTY.with(
            userUuid = NINA,
            member = VaultMemberProfile(profileEncryptionKey = ninaNewKey, isFromOwner = true, fullName = "Nina Roux"),
        )
        tables.add(member(NINA, key = ninaKey, fullName = "Nina Petit"))

        assertIs<Result.Success<Unit>>(members.backUp())

        val held = vault.book.members.getValue(NINA)

        assertContentEquals(ninaNewKey, held.profileEncryptionKey)
        assertTrue(held.isFromOwner)
        assertEquals("Nina Roux", held.fullName)
        assertTrue(row(NINA).isBackedUp)
    }

    @Test
    fun `a backup that loses a race merges again and keeps both writes`() = runBlocking<Unit> {
        vault.racingWrites += mapOf(
            PAUL to VaultMemberProfile(profileEncryptionKey = paulKey, isFromOwner = true, fullName = "Paul Roux"),
        )
        tables.add(member(NINA, key = ninaKey))

        assertIs<Result.Success<Unit>>(members.backUp())

        assertEquals(setOf(NINA, PAUL), vault.book.members.keys)
        assertTrue(row(NINA).isBackedUp)
    }

    @Test
    fun `a backup that keeps losing races is reported and leaves the members to write`() = runBlocking<Unit> {
        repeat(times = 3) {
            vault.racingWrites += mapOf(
                PAUL to VaultMemberProfile(profileEncryptionKey = paulKey, isFromOwner = false, fullName = null),
            )
        }
        tables.add(member(NINA, key = ninaKey))

        val backUp = members.backUp()

        assertEquals(GroupSyncError.Vault(VaultError.Contended), assertIs<Result.Error<GroupSyncError>>(backUp).error)
        assertFalse(row(NINA).isBackedUp)
        assertFalse(NINA in vault.book.members)
    }

    @Test
    fun `a member that changes during the write stays to back up`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey))
        vault.duringWrite = {
            members.takeOwnerKey(userUuid = NINA, profileEncryptionKey = ninaNewKey, sentAt = SENT_AT)
        }

        assertIs<Result.Success<Unit>>(members.backUp())

        assertFalse(row(NINA).isBackedUp)
        assertContentEquals(ninaNewKey, row(NINA).profileEncryptionKey)
    }

    @Test
    fun `a vault that cannot be written is reported and leaves every member to write`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaKey))
        vault.failsWriting = true

        val backUp = members.backUp()

        assertEquals(
            GroupSyncError.Vault(VaultError.Network(NetworkError.UNREACHABLE)),
            assertIs<Result.Error<GroupSyncError>>(backUp).error,
        )
        assertFalse(row(NINA).isBackedUp)
    }

    @Test
    fun `a restore puts the members and their names back`() = runBlocking<Unit> {
        vault.book = MemberProfileBook.EMPTY
            .with(
                userUuid = NINA,
                member = VaultMemberProfile(profileEncryptionKey = ninaKey, isFromOwner = true, fullName = "Nina Petit"),
            )
            .with(
                userUuid = PAUL,
                member = VaultMemberProfile(profileEncryptionKey = paulKey, isFromOwner = false, fullName = null),
            )

        assertIs<Result.Success<Unit>>(members.restore())

        val nina = row(NINA)

        assertContentEquals(ninaKey, nina.profileEncryptionKey)
        assertTrue(nina.isFromOwner)
        assertEquals("Nina Petit", nina.fullName)
        assertTrue(nina.isBackedUp)
        assertNull(row(PAUL).fullName)
        assertFalse(row(PAUL).isFromOwner)
        assertFalse(toRestore)
    }

    /** The book does not say which key a name was read with. */
    @Test
    fun `the names restored are read again`() = runBlocking<Unit> {
        upload(NINA, key = ninaKey, fullName = "Nina Roux")
        vault.book = MemberProfileBook.EMPTY.with(
            userUuid = NINA,
            member = VaultMemberProfile(profileEncryptionKey = ninaKey, isFromOwner = true, fullName = "Nina Petit"),
        )

        members.restore()
        members.readNames()

        assertEquals("Nina Roux", row(NINA).fullName)
        assertFalse(row(NINA).isBackedUp)
    }

    @Test
    fun `the members are restored once`() = runBlocking<Unit> {
        members.restore()

        vault.book = MemberProfileBook.EMPTY.with(
            userUuid = NINA,
            member = VaultMemberProfile(profileEncryptionKey = ninaKey, isFromOwner = true, fullName = "Nina Petit"),
        )

        members.restore()

        assertEquals(1, vault.reads)
        assertTrue(tables.rows.isEmpty())
    }

    @Test
    fun `a restore that cannot read the vault is tried again`() = runBlocking<Unit> {
        vault.book = MemberProfileBook.EMPTY.with(
            userUuid = NINA,
            member = VaultMemberProfile(profileEncryptionKey = ninaKey, isFromOwner = true, fullName = "Nina Petit"),
        )
        vault.failsReading = true

        assertIs<Result.Error<GroupSyncError>>(members.restore())
        assertTrue(toRestore)

        vault.failsReading = false

        assertIs<Result.Success<Unit>>(members.restore())
        assertEquals("Nina Petit", row(NINA).fullName)
    }

    @Test
    fun `a member held already keeps their row through a restore`() = runBlocking<Unit> {
        tables.add(member(NINA, key = ninaNewKey, isFromOwner = true))
        vault.book = MemberProfileBook.EMPTY.with(
            userUuid = NINA,
            member = VaultMemberProfile(profileEncryptionKey = ninaKey, isFromOwner = true, fullName = "Nina Petit"),
        )

        members.restore()

        assertContentEquals(ninaNewKey, row(NINA).profileEncryptionKey)
    }

    private fun member(
        userUuid: String,
        key: ByteArray,
        isFromOwner: Boolean = false,
        fullName: String? = null,
        nameKeyHash: String? = null,
        isBackedUp: Boolean = false,
    ) = MemberProfileEntity(
        userUuid = userUuid,
        profileEncryptionKey = key,
        isFromOwner = isFromOwner,
        ownerKeySentAt = null,
        fullName = fullName,
        nameKeyHash = nameKeyHash,
        isBackedUp = isBackedUp,
    )

    private fun contact(userUuid: String, key: ByteArray) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = key,
            handedProfileKeyHash = null,
            verifiedSafetyNumber = null,
            isBackedUp = true,
        )
}
