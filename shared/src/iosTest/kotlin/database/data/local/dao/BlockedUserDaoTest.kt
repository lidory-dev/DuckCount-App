package database.data.local.dao

import database.data.local.entity.BlockedUserEntity
import database.data.local.entity.ContactRequestEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

/**
 * Who the user blocked, see [BlockedUserEntity], run on the database itself: the backup, see
 * `BlockedUserRepository.backUp`, takes its word for which rows the vault holds, and the request
 * list for whose requests the user sees.
 *
 * Mallory is somebody the user blocks, and Alice somebody they do not.
 */
class BlockedUserDaoTest {

    @Test
    fun `a block is waiting for the vault until marked backed up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))

            assertTrue(dao.isBlocked(userUuid = MALLORY))
            assertEquals(listOf(MALLORY), dao.getNotBackedUp().map { it.userUuid })

            dao.markBackedUp(userUuid = MALLORY, fullName = "Mallory")

            assertTrue(dao.getNotBackedUp().isEmpty())
            assertTrue(dao.isBlocked(userUuid = MALLORY))
        }
    }

    /** Gone at once, the block would come back with the vault after a log-out. */
    @Test
    fun `an unblock leaves a tombstone for the vault that blocks nobody`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory", isBackedUp = true))
            dao.unblock(userUuid = MALLORY)

            assertFalse(dao.isBlocked(userUuid = MALLORY))
            assertTrue(dao.getBlockedUuids().isEmpty())
            assertTrue(dao.getBlockedAsFlow().first().isEmpty())
            assertEquals(listOf(true), dao.getNotBackedUp().map { it.isUnblocked })

            dao.deleteUnblocked(userUuids = listOf(MALLORY))

            assertTrue(dao.getNotBackedUp().isEmpty())
        }
    }

    /** The write carried the block, which the user lifted meanwhile: the tombstone waits for the next one. */
    @Test
    fun `a block lifted during its backup is not marked backed up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))
            dao.unblock(userUuid = MALLORY)

            dao.markBackedUp(userUuid = MALLORY, fullName = "Mallory")

            assertEquals(listOf(true), dao.getNotBackedUp().map { it.isUnblocked })
        }
    }

    /** The write carried the old name: the new one waits for the next one. */
    @Test
    fun `a block made again under another name during its backup is not marked backed up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = null))
            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))

            dao.markBackedUp(userUuid = MALLORY, fullName = null)

            assertEquals(listOf("Mallory"), dao.getNotBackedUp().map { it.fullName })
        }
    }

    /** The write dropped them from the vault, which the next one writes back. */
    @Test
    fun `a user blocked again during the backup of their unblock keeps their block`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory", isBackedUp = true))
            dao.unblock(userUuid = MALLORY)
            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))

            dao.deleteUnblocked(userUuids = listOf(MALLORY))

            assertTrue(dao.isBlocked(userUuid = MALLORY))
            assertEquals(listOf(MALLORY), dao.getNotBackedUp().map { it.userUuid })
        }
    }

    /** The vault's copy comes back after a log-out, under what this device did meanwhile. */
    @Test
    fun `what the vault brings back leaves a row this device has as it is`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val dao = database.blockedUserDao

            dao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))
            dao.unblock(userUuid = MALLORY)

            dao.insertNew(
                blocked = listOf(
                    BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory", isBackedUp = true),
                    BlockedUserEntity(userUuid = ALICE, fullName = "Alice", isBackedUp = true),
                ),
            )

            assertFalse(dao.isBlocked(userUuid = MALLORY))
            assertEquals(listOf(ALICE), dao.getBlockedUuids())
        }
    }

    /** Refused by the next refresh, and gone from the user's sight until then. */
    @Test
    fun `a request from somebody the user blocked is neither counted nor listed`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val requests = database.contactRequestDao

            requests.insertNew(
                contactRequests = listOf(openedRequest(uuid = "a", from = ALICE), openedRequest(uuid = "m", from = MALLORY)),
            )

            assertEquals(2, requests.getCountAsFlow().first())
            assertTrue(requests.getStandingAsFlow(userUuid = MALLORY).first().hasRequestFrom)

            database.blockedUserDao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))

            assertEquals(1, requests.getCountAsFlow().first())
            assertEquals(listOf(ALICE), requests.getOpenedAsFlow().first().map { it.senderUserUuid })
            assertFalse(requests.getStandingAsFlow(userUuid = MALLORY).first().hasRequestFrom)
        }
    }

    /** Unblocked, they are somebody whose request the user may answer again. */
    @Test
    fun `a request from somebody the user unblocked is listed again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val requests = database.contactRequestDao

            requests.insertNew(contactRequests = listOf(openedRequest(uuid = "m", from = MALLORY)))
            database.blockedUserDao.upsert(BlockedUserEntity(userUuid = MALLORY, fullName = "Mallory"))
            database.blockedUserDao.unblock(userUuid = MALLORY)

            assertEquals(1, requests.getCountAsFlow().first())
            assertEquals(listOf(MALLORY), requests.getOpenedAsFlow().first().map { it.senderUserUuid })
        }
    }

    private fun openedRequest(uuid: String, from: String) = ContactRequestEntity(
        uuid = uuid,
        senderUserUuid = from,
        ciphertext = "",
        createdAt = "2026-11-05T12:00:00Z",
        mlsGroupId = byteArrayOf(1, 2, 3),
        profileEncryptionKey = ByteArray(32),
        senderFullName = from,
    )
}
