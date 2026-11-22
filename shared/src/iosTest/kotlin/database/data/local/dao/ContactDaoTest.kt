package database.data.local.dao

import database.data.local.entity.ContactEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private val KEY = ByteArray(32) { 1 }

/** The partial hash of the key to the user's own profile Alice was handed. */
private const val HANDED_HASH = "handed"

/**
 * What [ContactDao] keeps of the edits contacts make to their profiles, run on the database itself,
 * whose queries `ContactTables` stands in for in the tests of the contacts: the hand-overs of a
 * contact's key, which have their profile read again, see [ContactEntity.unreadProfileUpdates], and
 * the user's own key owed again to every contact once they edit theirs.
 *
 * Alice is a contact of the user's, and Bob one they removed.
 */
class ContactDaoTest {

    @Test
    fun `a contact handing over their key is due a read until it is read`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.contactDao

            dao.upsert(contactOf(ALICE))

            assertTrue(dao.getUpdatedSinceRead().isEmpty())

            dao.countProfileUpdate(userUuid = ALICE)
            dao.countProfileUpdate(userUuid = ALICE)

            val due = dao.getUpdatedSinceRead().single()

            assertEquals(ALICE, due.userUuid)
            assertEquals(2, due.unreadProfileUpdates)

            dao.markProfileRead(userUuid = ALICE, count = 2)

            assertTrue(dao.getUpdatedSinceRead().isEmpty())
        }
    }

    /** The read may have been made before the edit the later hand-over tells of. */
    @Test
    fun `a hand-over during a read keeps the contact due`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.contactDao

            dao.upsert(contactOf(ALICE))
            dao.countProfileUpdate(userUuid = ALICE)

            val read = dao.getUpdatedSinceRead().single()

            dao.countProfileUpdate(userUuid = ALICE)
            dao.markProfileRead(userUuid = ALICE, count = read.unreadProfileUpdates)

            assertEquals(2, dao.getUpdatedSinceRead().single().unreadProfileUpdates)
        }
    }

    @Test
    fun `a former contact handing over their key counts nothing`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.contactDao

            dao.upsert(contactOf(BOB, isRemoved = true))
            dao.countProfileUpdate(userUuid = BOB)

            assertTrue(dao.getUpdatedSinceRead().isEmpty())
        }
    }

    /** For the vault to hold that she is owed it, should the user log out before it goes out. */
    @Test
    fun `an edit owes every contact the key again and no former one`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.contactDao

            dao.upsert(contactOf(ALICE))
            dao.upsert(contactOf(BOB, isRemoved = true))

            assertEquals(1, dao.oweProfileKeyAgain())

            val alice = dao.get(userUuid = ALICE)!!

            assertNull(alice.handedProfileKeyHash)
            assertFalse(alice.isBackedUp)
            assertEquals(HANDED_HASH, dao.getNotBackedUp().single { it.userUuid == BOB }.handedProfileKeyHash)
        }
    }

    @Test
    fun `an edit with no contact owes nobody`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            assertEquals(0, database.contactDao.oweProfileKeyAgain())
        }
    }
}

/** A contact of the user's handed their key and backed up, or one they removed when [isRemoved]. */
private fun contactOf(userUuid: String, isRemoved: Boolean = false) =
    ContactEntity(
        userUuid = userUuid,
        profileEncryptionKey = KEY,
        handedProfileKeyHash = HANDED_HASH,
        verifiedSafetyNumber = null,
        isBackedUp = !isRemoved,
        isRemoved = isRemoved,
    )
