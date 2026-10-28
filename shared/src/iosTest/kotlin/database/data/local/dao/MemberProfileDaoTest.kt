package database.data.local.dao

import database.data.local.entity.ContactEntity
import database.data.local.entity.MemberProfileEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

/** Keys to Carol's profile, the older first. */
private val OLDER_KEY = ByteArray(32) { 1 }
private val NEWER_KEY = ByteArray(32) { 2 }

/** When the server stored messages Carol handed her keys over in, in milliseconds since the epoch, the earliest first. */
private const val OLDER_POST = 1_761_566_400_000L
private const val NEWER_POST = 1_761_566_460_000L
private const val LATEST_POST = 1_761_566_520_000L

/**
 * What [MemberProfileDao] keeps of the keys and names the groups handed over, run on the database
 * itself, whose queries `MemberProfileTables` stands in for in the tests of the group sync.
 */
class MemberProfileDaoTest {

    @Test
    fun `a key from its owner replaces a relayed one and the name shown stays until read again`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                val dao = database.memberProfileDao

                dao.fillGap(userUuid = CAROL, profileEncryptionKey = OLDER_KEY)
                dao.setName(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, fullName = "Carol", nameKeyHash = "older")
                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)

                val carols = assertNotNull(dao.get(userUuid = CAROL))

                assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
                assertTrue(carols.isFromOwner)
                assertEquals(NEWER_POST, carols.ownerKeySentAt)
                assertEquals("Carol", carols.fullName)
                assertEquals("older", carols.nameKeyHash)
                assertFalse(carols.isBackedUp)
            }
        }

    @Test
    fun `a relayed key only fills in for a member nothing is held for`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.fillGap(userUuid = CAROL, profileEncryptionKey = OLDER_KEY)
            dao.fillGap(userUuid = CAROL, profileEncryptionKey = NEWER_KEY)

            assertContentEquals(OLDER_KEY, dao.getKey(userUuid = CAROL))

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.fillGap(userUuid = CAROL, profileEncryptionKey = OLDER_KEY)

            assertContentEquals(NEWER_KEY, dao.getKey(userUuid = CAROL))
        }
    }

    /**
     * A member posts their key into every group they are in, and each post is read, in whatever
     * order: the latest is what an older key has to lose to, and the vault, which holds no time,
     * holds the row as it is.
     */
    @Test
    fun `the very key from its owner again only moves the time it came in up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = OLDER_POST)
            dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol", nameKeyHash = "newer")
            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = "Carol")
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = LATEST_POST)
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
            assertEquals(LATEST_POST, carols.ownerKeySentAt)
            assertEquals("Carol", carols.fullName)
            assertEquals("newer", carols.nameKeyHash)
            assertTrue(carols.isBackedUp)
        }
    }

    /**
     * A Welcome, or a map, Carol built as a group's creator before her key rotated, and sent after
     * it, between two of her posts of the new one into other groups, read after both.
     */
    @Test
    fun `an older key from its owner sent between two posts of the newer one keeps the newer one`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                val dao = database.memberProfileDao

                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = OLDER_POST)
                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = LATEST_POST)
                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, sentAt = NEWER_POST)

                val carols = assertNotNull(dao.get(userUuid = CAROL))

                assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
                assertEquals(LATEST_POST, carols.ownerKeySentAt)
            }
        }

    /**
     * Read in a group read after the one holding the newer post, or in a later pass: Carol was
     * removed from it before she posted her new key there.
     */
    @Test
    fun `an older key from its owner read after a newer one keeps the newer one`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol", nameKeyHash = "newer")
            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = "Carol")

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, sentAt = OLDER_POST)

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
            assertEquals(NEWER_POST, carols.ownerKeySentAt)
            assertEquals("newer", carols.nameKeyHash)
            assertTrue(carols.isBackedUp)
        }
    }

    @Test
    fun `a newer key from its owner replaces an older one`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, sentAt = OLDER_POST)
            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, isFromOwner = true, fullName = null)

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
            assertEquals(NEWER_POST, carols.ownerKeySentAt)
            assertFalse(carols.isBackedUp)
        }
    }

    /** Two posts stored at the same millisecond say nothing of which key is the newer. */
    @Test
    fun `another key from its owner sent at the same time leaves the one held`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, sentAt = NEWER_POST)
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)

            assertContentEquals(OLDER_KEY, dao.getKey(userUuid = CAROL))
        }
    }

    /**
     * Brought back after a log-in, onto a device holding no MLS state: every post it reads from
     * then on was sent after any the vault's keys came in.
     */
    @Test
    fun `a key from its owner replaces one the vault brought back whenever it was sent`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.insertNew(
                listOf(
                    MemberProfileEntity(
                        userUuid = CAROL,
                        profileEncryptionKey = OLDER_KEY,
                        isFromOwner = true,
                        ownerKeySentAt = null,
                        fullName = "Carol",
                        nameKeyHash = null,
                        isBackedUp = true,
                    ),
                ),
            )

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = OLDER_POST)

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
            assertEquals(OLDER_POST, carols.ownerKeySentAt)
            assertEquals("Carol", carols.fullName)
        }
    }

    /** Brought back after a log-in, and posted again by Carol, who still holds it. */
    @Test
    fun `the very key from its owner gives one the vault brought back the time it came in`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.insertNew(
                listOf(
                    MemberProfileEntity(
                        userUuid = CAROL,
                        profileEncryptionKey = NEWER_KEY,
                        isFromOwner = true,
                        ownerKeySentAt = null,
                        fullName = "Carol",
                        nameKeyHash = null,
                        isBackedUp = true,
                    ),
                ),
            )

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertEquals(NEWER_POST, carols.ownerKeySentAt)
            assertTrue(carols.isBackedUp)
        }
    }

    @Test
    fun `a name read with a key replaced during the read is not saved`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.fillGap(userUuid = CAROL, profileEncryptionKey = OLDER_KEY)
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.setName(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, fullName = "Carol", nameKeyHash = "older")

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertNull(carols.fullName)
            assertNull(carols.nameKeyHash)
        }
    }

    @Test
    fun `a name that changed is for the vault to take and one read again unchanged is not`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                val dao = database.memberProfileDao

                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
                dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol", nameKeyHash = "newer")
                dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = "Carol")

                dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol", nameKeyHash = "newer")

                assertTrue(assertNotNull(dao.get(userUuid = CAROL)).isBackedUp)

                dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol Blanc", nameKeyHash = "newer")

                assertFalse(assertNotNull(dao.get(userUuid = CAROL)).isBackedUp)
                assertEquals(listOf(CAROL), dao.getNotBackedUp().map { it.userUuid })
            }
        }

    /** Its owner deleted their account, or rotated their key ten times since. */
    @Test
    fun `a read settled without a name keeps the one read before`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, sentAt = OLDER_POST)
            dao.setName(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, fullName = "Carol", nameKeyHash = "older")
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.settleRead(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, nameKeyHash = "newer")

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertEquals("Carol", carols.fullName)
            assertEquals("newer", carols.nameKeyHash)
        }
    }

    /** Read before she became a contact, under the key she still has, and she renamed herself since. */
    @Test
    fun `a former contact is named as the contact list named them and read again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "carol", nameKeyHash = "newer")
            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = "carol")

            dao.takeFormerContactName(userUuid = CAROL, fullName = "Carol Blanc")

            val carols = assertNotNull(dao.get(userUuid = CAROL))

            assertEquals("Carol Blanc", carols.fullName)
            assertNull(carols.nameKeyHash)
            assertContentEquals(NEWER_KEY, carols.profileEncryptionKey)
            assertTrue(carols.isFromOwner)
            assertFalse(carols.isBackedUp)
        }
    }

    @Test
    fun `a former contact the contact list had no name for keeps the one read and is read again`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                val dao = database.memberProfileDao

                dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
                dao.setName(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, fullName = "Carol", nameKeyHash = "newer")
                dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = "Carol")

                dao.takeFormerContactName(userUuid = CAROL, fullName = null)

                val carols = assertNotNull(dao.get(userUuid = CAROL))

                assertEquals("Carol", carols.fullName)
                assertNull(carols.nameKeyHash)
                assertTrue(carols.isBackedUp)
            }
        }

    @Test
    fun `a former contact no group named is given no row`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.memberProfileDao.takeFormerContactName(userUuid = CAROL, fullName = "Carol")

            assertNull(database.memberProfileDao.get(userUuid = CAROL))
        }
    }

    /** The contact sync reads a contact's name, with the key they handed over themselves. */
    @Test
    fun `the names to read are those of everybody but the current contacts`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.contactDao.upsert(contactOf(ALICE, isRemoved = false))
            database.contactDao.upsert(contactOf(BOB, isRemoved = true))

            listOf(ALICE, BOB, CAROL).forEach {
                database.memberProfileDao.fillGap(userUuid = it, profileEncryptionKey = OLDER_KEY)
            }

            assertEquals(setOf(BOB, CAROL), database.memberProfileDao.getNotContacts().mapTo(HashSet()) { it.userUuid })
        }
    }

    @Test
    fun `a row that changed as it was backed up stays to back up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.memberProfileDao

            dao.fillGap(userUuid = CAROL, profileEncryptionKey = OLDER_KEY)
            dao.takeOwnerKey(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, sentAt = NEWER_POST)
            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = OLDER_KEY, isFromOwner = false, fullName = null)

            assertFalse(assertNotNull(dao.get(userUuid = CAROL)).isBackedUp)

            dao.markBackedUp(userUuid = CAROL, profileEncryptionKey = NEWER_KEY, isFromOwner = true, fullName = null)

            assertTrue(assertNotNull(dao.get(userUuid = CAROL)).isBackedUp)
        }
    }

    @Test
    fun `a log-out empties the keys and names the groups handed over`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.memberProfileDao.takeOwnerKey(
                userUuid = CAROL,
                profileEncryptionKey = NEWER_KEY,
                sentAt = NEWER_POST,
            )

            database.localDataDao.deleteAll()

            assertNull(database.memberProfileDao.get(userUuid = CAROL))
        }
    }
}

/** A contact of the user's, or one they removed when [isRemoved]. */
private fun contactOf(userUuid: String, isRemoved: Boolean) =
    ContactEntity(
        userUuid = userUuid,
        profileEncryptionKey = NEWER_KEY,
        handedProfileKeyHash = null,
        verifiedSafetyNumber = null,
        isBackedUp = true,
        isRemoved = isRemoved,
    )
