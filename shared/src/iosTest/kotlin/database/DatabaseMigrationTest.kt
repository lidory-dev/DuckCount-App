package database

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"

/** The identity of version 1, as `shared/schemas/database.AppDatabase/1.json` records what v1.0.0 shipped. */
private const val VERSION_1_IDENTITY_HASH = "e52733787107070a4fbba3ba59f1d956"

/**
 * What a database a released build wrote comes to once a later one opens it, see the versions in
 * [AppDatabase]: Room runs the migrations, then checks every table against what this build expects,
 * and an install whose migration is missing or wrong would end the app as it opens.
 *
 * On a file, as an install's is, and on the system's SQLite, as [withInMemoryDatabase] is: what is
 * migrated is the schema, which SQLCipher leaves as it is.
 */
class DatabaseMigrationTest {

    private val path = NSTemporaryDirectory() + "migration-${NSUUID().UUIDString}.db"

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun deleteFiles() {
        listOf(path, "$path-wal", "$path-shm", "$path-journal").forEach {
            NSFileManager.defaultManager.removeItemAtPath(it, error = null)
        }
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(NativeSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    /**
     * Version 1 is version 2 without `ContactEntity.unreadProfileUpdates`, the one table that
     * changed: so the file is made by this build, the column taken off and the version put back.
     */
    private suspend fun writeVersion1WithAlice() {
        open().apply { contactDao.getUserUuids() }.close()

        val connection = NativeSQLiteDriver().open(path)

        connection.execSQL("ALTER TABLE ContactEntity DROP COLUMN unreadProfileUpdates")
        connection.execSQL("UPDATE room_master_table SET identity_hash = '$VERSION_1_IDENTITY_HASH'")
        connection.execSQL("PRAGMA user_version = 1")
        connection.execSQL(
            """INSERT INTO ContactEntity
            (userUuid, profileEncryptionKey, handedProfileKeyHash, verifiedSafetyNumber, isBackedUp, isRemoved, rotatesProfileKey)
            VALUES ('$ALICE', x'0101', 'handed', NULL, 1, 0, 0)""",
        )
        connection.close()
    }

    @Test
    fun `a contact written by version 1 comes through to version 2 read as never updated`() = runBlocking<Unit> {
        writeVersion1WithAlice()

        val database = open()

        try {
            val alice = assertNotNull(database.contactDao.get(userUuid = ALICE))

            assertContentEquals(byteArrayOf(1, 1), alice.profileEncryptionKey)
            assertEquals("handed", alice.handedProfileKeyHash)
            assertTrue(alice.isBackedUp)
            assertEquals(0, alice.unreadProfileUpdates)
            assertTrue(database.contactDao.getUpdatedSinceRead().isEmpty())

            database.contactDao.countProfileUpdate(userUuid = ALICE)

            assertEquals(listOf(ALICE), database.contactDao.getUpdatedSinceRead().map { it.userUuid })
        } finally {
            database.close()
        }
    }
}
