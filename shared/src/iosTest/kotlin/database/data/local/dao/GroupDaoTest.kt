package database.data.local.dao

import androidx.room.execSQL
import androidx.room.useWriterConnection
import database.data.local.entity.GroupEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import group.FIRST_GROUP_REVISION
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** A group's key, and the one its creator handed over in its place. */
private val OLD_KEY = ByteArray(32) { 1 }
private val NEW_KEY = ByteArray(32) { 2 }

/** The partial hash of the key to the user's profile as it is now, and of the one it replaced. */
private const val CURRENT_HASH = "current"
private const val OLDER_HASH = "older"

/**
 * Which groups the key to the user's own profile is posted into, see
 * [GroupDao.getOwedProfileKey], run on the database itself: `group.GroupProfileKeyDelivery`
 * takes its word for it. And how a group the user leaves is recorded, see
 * [GroupDao.markLeftAndOweLeave], which `group.GroupLeaver` takes for done once it returns, and
 * when a group is recorded as backed up, see [GroupEntity.isBackedUp], which
 * `group.GroupKeyCopies` puts up no copy again for while the server lists one. And what a version
 * of its information shown leaves of it, see [GroupDao.updateInformation].
 */
class GroupDaoTest {

    @Test
    fun `a group holding an older key for the user or none is owed the current one`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP, handedProfileKeyHash = null))
            database.groupDao.insert(groupOf(OTHER_GROUP, handedProfileKeyHash = OLDER_HASH))

            assertEquals(setOf(GROUP, OTHER_GROUP), database.groupDao.owed())
        }
    }

    @Test
    fun `a group holding the current key is owed nothing`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP, handedProfileKeyHash = CURRENT_HASH))

            assertTrue(database.groupDao.owed().isEmpty())
        }
    }

    /** Nothing can be posted into either: the user is no longer a member, or this device holds no MLS state for it. */
    @Test
    fun `a group the user was removed from or brought back after a log-out is owed nothing`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP, isLeft = true))
                database.groupDao.insert(groupOf(OTHER_GROUP, mlsGroupId = null))

                assertTrue(database.groupDao.owed().isEmpty())
            }
        }

    /** What MLS says of who sent the post there is the word of a creator this device no longer trusts. */
    @Test
    fun `a group whose creator is blocked is owed nothing`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.identityConflictDao.insert(IdentityConflictEntity(peerUuid = MALLORY))
            database.groupDao.insert(groupOf(GROUP, ownerUuid = MALLORY))
            database.groupDao.insert(groupOf(OTHER_GROUP))

            assertEquals(setOf(OTHER_GROUP), database.groupDao.owed())
        }
    }

    /**
     * The post would go out in an epoch the change takes the group from, which those it seats
     * cannot read, or into a group the server does not hold yet.
     */
    @Test
    fun `a group with a creation or a change to its members pending is owed nothing until it is settled`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP))
                database.groupDao.insertPendingCreation(PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"))
                database.groupDao.insert(groupOf(OTHER_GROUP))
                database.groupDao.upsertMembershipChange(
                    PendingMembershipChangeEntity(
                        groupUuid = OTHER_GROUP,
                        kind = MembershipChangeKind.ADDITION,
                        body = "{}",
                        commitEpoch = 1,
                        groupKey = null,
                    ),
                )

                assertTrue(database.groupDao.owed().isEmpty())

                database.groupDao.deleteMembershipChange(groupUuid = OTHER_GROUP)

                assertEquals(setOf(OTHER_GROUP), database.groupDao.owed())
            }
        }

    @Test
    fun `a group the user leaves is marked left with its leave owed`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP))
            database.groupDao.insert(groupOf(OTHER_GROUP))

            database.groupDao.markLeftAndOweLeave(groupUuid = GROUP)

            assertTrue(assertNotNull(database.groupDao.get(uuid = GROUP)).isLeft)
            assertFalse(assertNotNull(database.groupDao.get(uuid = OTHER_GROUP)).isLeft)
            assertEquals(listOf(GROUP), database.groupDao.getDeclinedUuids())
        }
    }

    /**
     * A trigger stands in for the database failing as the leave is recorded owed: a group left
     * with no leave owed would keep the user a member on the server for good.
     */
    @Test
    fun `a group whose leave cannot be recorded owed is not marked left either`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP))
            database.useWriterConnection { connection ->
                connection.execSQL(
                    "CREATE TRIGGER failing BEFORE INSERT ON DeclinedGroupEntity BEGIN SELECT RAISE(ABORT, 'failing'); END",
                )
            }

            assertFails { database.groupDao.markLeftAndOweLeave(groupUuid = GROUP) }

            assertFalse(assertNotNull(database.groupDao.get(uuid = GROUP)).isLeft)
            assertTrue(database.groupDao.getDeclinedUuids().isEmpty())
        }
    }

    @Test
    fun `a key handed over in the group is not backed up until its own copy goes up`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP, groupKey = OLD_KEY, isBackedUp = true))

            database.groupDao.updateGroupKey(uuid = GROUP, groupKey = NEW_KEY)

            assertFalse(database.groupDao.isBackedUp())
            assertContentEquals(NEW_KEY, assertNotNull(database.groupDao.get(uuid = GROUP)).groupKey)
        }
    }

    /** The rotation carried the creator's copy of the key it hands over. */
    @Test
    fun `a key rotation taken backs the group up and is dropped`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP, groupKey = OLD_KEY, isBackedUp = true))
            database.groupDao.upsertMembershipChange(
                PendingMembershipChangeEntity(
                    groupUuid = GROUP,
                    kind = MembershipChangeKind.KEY_ROTATION,
                    body = "{}",
                    commitEpoch = null,
                    groupKey = NEW_KEY,
                ),
            )

            database.groupDao.adoptRotatedKey(groupUuid = GROUP, groupKey = NEW_KEY)

            assertTrue(database.groupDao.isBackedUp())
            assertContentEquals(NEW_KEY, assertNotNull(database.groupDao.get(uuid = GROUP)).groupKey)
            assertTrue(database.groupDao.getMembershipChanges().isEmpty())
        }
    }

    /** The creation carried the creator's copy of the key the group was created with. */
    @Test
    fun `a creation taken backs the group up and is dropped`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insertCreated(
                group = groupOf(GROUP, groupKey = OLD_KEY, isBackedUp = false),
                members = emptyList(),
                pendingCreation = PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"),
            )

            database.groupDao.deleteTakenCreation(groupUuid = GROUP)

            assertTrue(database.groupDao.isBackedUp())
            assertTrue(database.groupDao.getPendingCreations().isEmpty())
        }
    }

    /** What an edit shown leaves of the group: the rest as it was, its currency among it. */
    @Test
    fun `a version shown replaces the title and the description and records its revision`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insert(groupOf(GROUP, groupKey = OLD_KEY))
            database.groupDao.insert(groupOf(OTHER_GROUP))

            database.groupDao.updateInformation(
                uuid = GROUP,
                title = "Ski trip 2027",
                description = "Chalet, lift passes and the train",
                infoRevision = 2,
            )

            val group = assertNotNull(database.groupDao.get(uuid = GROUP))

            assertEquals("Ski trip 2027", group.title)
            assertEquals("Chalet, lift passes and the train", group.description)
            assertEquals(2L, group.infoRevision)
            assertEquals(CurrencyCatalog.defaultCurrency, group.currency)
            assertContentEquals(OLD_KEY, group.groupKey)
            assertEquals(FIRST_GROUP_REVISION, assertNotNull(database.groupDao.get(uuid = OTHER_GROUP)).infoRevision)
        }
    }
}

/** Whether the group [GROUP] is recorded as backed up. */
private suspend fun GroupDao.isBackedUp(): Boolean = assertNotNull(get(uuid = GROUP)).isBackedUp

/** The UUIDs of the groups owed the key whose partial hash is [CURRENT_HASH]. */
private suspend fun GroupDao.owed(): Set<String> =
    getOwedProfileKey(profileKeyHash = CURRENT_HASH).mapTo(HashSet()) { it.uuid }

/** A group of Alice's, unless [ownerUuid] says otherwise, that the user is in and holds the MLS state of. */
private fun groupOf(
    uuid: String,
    ownerUuid: String = ALICE,
    mlsGroupId: ByteArray? = uuid.encodeToByteArray(),
    groupKey: ByteArray = ByteArray(32),
    isBackedUp: Boolean = true,
    isLeft: Boolean = false,
    handedProfileKeyHash: String? = null,
) = GroupEntity(
    uuid = uuid,
    title = "Ski trip",
    description = "",
    currency = CurrencyCatalog.defaultCurrency,
    infoRevision = FIRST_GROUP_REVISION,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
