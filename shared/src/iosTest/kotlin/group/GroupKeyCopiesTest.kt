package group

import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** What the server lists of a group's fields, which nothing here reads. */
private val SEALED = EncryptedFieldDto(ciphertext = "dA==", nonce = "bg==")

/**
 * What becomes of the user's copies of their groups' keys, see [GroupKeyCopies]: sealed so as to
 * open as their own group's alone, opened from what the server lists, and put up wherever the
 * listing shows none, or this device holds a key whose copy has not gone up. The database and
 * the server are stood in for, the sealing is the library's own.
 */
class GroupKeyCopiesTest {

    private val server = ServerKeyCopies()

    private val groups = mutableMapOf<String, GroupEntity>()

    private val keyCopies = server.groupKeyCopies(groups)

    @Test
    fun `a copy opens as the key of the group it was sealed for`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()
        val copy = assertIs<Result.Success<EncryptedFieldDto>>(keyCopies.seal(groupUuid = GROUP, groupKey = groupKey))

        val opened = assertIs<Result.Success<Map<String, ByteArray>>>(
            keyCopies.open(listOf(listingOf(GROUP, keyCopy = copy.data))),
        )

        assertEquals(setOf(GROUP), opened.data.keys)
        assertContentEquals(groupKey, opened.data.getValue(GROUP))
    }

    /** As the server moving one group's copy to another would have it: it opens as no group's key. */
    @Test
    fun `a copy listed with another group than the one it was sealed for does not open`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        val opened = keyCopies.open(
            listOf(
                listingOf(GROUP, keyCopy = server.copyOf(groupKey = groupKey, sealedFor = OTHER_GROUP)),
                listingOf(OTHER_GROUP, keyCopy = server.copyOf(groupKey = groupKey, sealedFor = OTHER_GROUP)),
            ),
        )

        assertEquals(setOf(OTHER_GROUP), assertIs<Result.Success<Map<String, ByteArray>>>(opened).data.keys)
    }

    @Test
    fun `groups listed with no copy open nothing and read no key ring`() = runBlocking<Unit> {
        assertEquals(Result.Success(emptyMap()), keyCopies.open(listOf(listingOf(GROUP, keyCopy = null))))

        assertEquals(0, server.keyRingReads)
    }

    @Test
    fun `a key ring that does not answer opens nothing and is reported`() = runBlocking<Unit> {
        server.isKeyRingAnswering = false

        val opened = keyCopies.open(
            listOf(listingOf(GROUP, keyCopy = server.copyOf(generateSymmetricEncryptionKey(), sealedFor = GROUP))),
        )

        assertEquals(Result.Error(VaultError.Network(NetworkError.UNREACHABLE)), opened)
    }

    @Test
    fun `a key ring that does not answer seals no copy`() = runBlocking<Unit> {
        server.isKeyRingAnswering = false

        assertEquals(
            Result.Error(VaultError.Network(NetworkError.UNREACHABLE)),
            keyCopies.seal(groupUuid = GROUP, groupKey = generateSymmetricEncryptionKey()),
        )
    }

    @Test
    fun `a group listed with no copy has its copy put up and is recorded backed up`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        groups[GROUP] = groupOf(GROUP, groupKey = groupKey, isBackedUp = false)

        assertEquals(Result.Success(Unit), keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = null))))

        assertContentEquals(groupKey, server.keyOf(GROUP))
        assertTrue(groups.getValue(GROUP).isBackedUp)
    }

    /** As once its creator removed the user and added them back: the server dropped it with the removal. */
    @Test
    fun `a copy the server dropped is put up again although the group was backed up`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        groups[GROUP] = groupOf(GROUP, groupKey = groupKey, isBackedUp = true)

        assertEquals(Result.Success(Unit), keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = null))))

        assertEquals(listOf(GROUP), server.putUp)
        assertContentEquals(groupKey, server.keyOf(GROUP))
    }

    /** The creator handed over a new key in the group: the copy the server lists holds the one it replaced. */
    @Test
    fun `a key that changed has its copy put up although the listing shows one`() = runBlocking<Unit> {
        val oldKey = generateSymmetricEncryptionKey()
        val newKey = generateSymmetricEncryptionKey()

        server.copies[GROUP] = server.copyOf(groupKey = oldKey, sealedFor = GROUP)
        groups[GROUP] = groupOf(GROUP, groupKey = newKey, isBackedUp = false)

        assertEquals(
            Result.Success(Unit),
            keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = server.copies.getValue(GROUP)))),
        )

        assertContentEquals(newKey, server.keyOf(GROUP))
        assertTrue(groups.getValue(GROUP).isBackedUp)
    }

    @Test
    fun `a group backed up whose copy is listed puts nothing up and reads no key ring`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        groups[GROUP] = groupOf(GROUP, groupKey = groupKey, isBackedUp = true)

        assertEquals(
            Result.Success(Unit),
            keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = server.copyOf(groupKey = groupKey, sealedFor = GROUP)))),
        )

        assertTrue(server.putUp.isEmpty())
        assertEquals(0, server.keyRingReads)
    }

    /**
     * Removed or left, which drops the user's copy on the server: the creator adding them back
     * hands over the key the group has then.
     */
    @Test
    fun `a group the user is no longer in has nothing put up`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false, isLeft = true)

        assertEquals(Result.Success(Unit), keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = null))))

        assertTrue(server.putUp.isEmpty())
    }

    /** The server takes a copy from a current member alone, and the listing is what decides. */
    @Test
    fun `a group the server does not list has nothing put up`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false)

        assertEquals(Result.Success(Unit), keyCopies.putUp(emptyList()))

        assertTrue(server.putUp.isEmpty())
        assertFalse(groups.getValue(GROUP).isBackedUp)
    }

    /** The next would fare no better, and the next sync finds both still owed. */
    @Test
    fun `a copy the server does not take stops the others and leaves its group not backed up`() =
        runBlocking<Unit> {
            groups[GROUP] = groupOf(GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false)
            groups[OTHER_GROUP] = groupOf(OTHER_GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false)
            server.saveAnswer = Result.Error(NetworkError.SERVER_ERROR)

            val putUp = keyCopies.putUp(
                listOf(listingOf(GROUP, keyCopy = null), listingOf(OTHER_GROUP, keyCopy = null)),
            )

            assertEquals(Result.Error(GroupSyncError.Network(NetworkError.SERVER_ERROR)), putUp)
            assertEquals(1, server.putUp.size)
            assertTrue(groups.values.none { it.isBackedUp })
        }

    /**
     * Alice removed the user from one group after the server listed it: that says nothing of the
     * next, whose copy the server takes, and the next listing, which shows the first no more,
     * decides about it.
     */
    @Test
    fun `a group the server no longer holds the user in is passed over and the others put up`() =
        runBlocking<Unit> {
            val otherKey = generateSymmetricEncryptionKey()

            groups[GROUP] = groupOf(GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false)
            groups[OTHER_GROUP] = groupOf(OTHER_GROUP, groupKey = otherKey, isBackedUp = false)
            server.notMemberOf += GROUP

            val putUp = keyCopies.putUp(
                listOf(listingOf(GROUP, keyCopy = null), listingOf(OTHER_GROUP, keyCopy = null)),
            )

            assertEquals(Result.Success(Unit), putUp)
            assertEquals(listOf(GROUP, OTHER_GROUP), server.putUp)
            assertFalse(groups.getValue(GROUP).isBackedUp)
            assertContentEquals(otherKey, server.keyOf(OTHER_GROUP))
            assertTrue(groups.getValue(OTHER_GROUP).isBackedUp)
        }

    @Test
    fun `a key ring that does not answer puts nothing up and is reported`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(GROUP, groupKey = generateSymmetricEncryptionKey(), isBackedUp = false)
        server.isKeyRingAnswering = false

        assertEquals(
            Result.Error(GroupSyncError.Vault(VaultError.Network(NetworkError.UNREACHABLE))),
            keyCopies.putUp(listOf(listingOf(GROUP, keyCopy = null))),
        )

        assertTrue(server.putUp.isEmpty())
        assertFalse(groups.getValue(GROUP).isBackedUp)
    }

    /** The group [uuid] as the server lists it to the user, with their copy of its key, [keyCopy], if any. */
    private fun listingOf(uuid: String, keyCopy: EncryptedFieldDto?) = GroupDto(
        uuid = uuid,
        ownerUuid = ALICE,
        creatorUuid = ALICE,
        title = SEALED,
        description = SEALED,
        currency = SEALED,
        revision = FIRST_GROUP_REVISION,
        signature = SEALED,
        members = listOf(GroupMemberDto(userUuid = ALICE)),
        keyCopy = keyCopy,
    )

    /** A group of Alice's as this device holds it, under [groupKey]. */
    private fun groupOf(uuid: String, groupKey: ByteArray, isBackedUp: Boolean, isLeft: Boolean = false) = GroupEntity(
        uuid = uuid,
        title = "Ski trip",
        description = "",
        currency = CurrencyCatalog.defaultCurrency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ALICE,
        mlsGroupId = uuid.encodeToByteArray(),
        groupKey = groupKey,
        isBackedUp = isBackedUp,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
