package database.data.local.dao

import database.data.local.entity.GroupEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** The partial hash of the key to the user's profile as it is now, and of the one it replaced. */
private const val CURRENT_HASH = "current"
private const val OLDER_HASH = "older"

/**
 * Which groups the key to the user's own profile is posted into, see
 * [GroupDao.getOwedProfileKey], run on the database itself: `group.GroupProfileKeyDelivery`
 * takes its word for it.
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
}

/** The UUIDs of the groups owed the key whose partial hash is [CURRENT_HASH]. */
private suspend fun GroupDao.owed(): Set<String> =
    getOwedProfileKey(profileKeyHash = CURRENT_HASH).mapTo(HashSet()) { it.uuid }

/** A group of Alice's, unless [ownerUuid] says otherwise, that the user is in and holds the MLS state of. */
private fun groupOf(
    uuid: String,
    ownerUuid: String = ALICE,
    mlsGroupId: ByteArray? = uuid.encodeToByteArray(),
    isLeft: Boolean = false,
    handedProfileKeyHash: String? = null,
) = GroupEntity(
    uuid = uuid,
    title = "Ski trip",
    description = "",
    currency = CurrencyCatalog.defaultCurrency,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = ByteArray(32),
    isBackedUp = true,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
