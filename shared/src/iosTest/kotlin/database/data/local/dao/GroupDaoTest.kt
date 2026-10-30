package database.data.local.dao

import androidx.room.execSQL
import androidx.room.useWriterConnection
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import group.FIRST_GROUP_REVISION
import group.FIRST_KEY_GENERATION
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** A group's key, and the one its creator handed over in its place, and the one after that. */
private val OLD_KEY = ByteArray(32) { 1 }
private val NEW_KEY = ByteArray(32) { 2 }
private val NEWER_KEY = ByteArray(32) { 3 }

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
 * of its information shown leaves of it, see [GroupDao.updateInformation]. And which generations of
 * a group's keys it keeps, see [GroupKeyEntity], and which signature keys of its members, see
 * [GroupMemberSignatureKeyEntity], which `group.GroupKeyChain` and the checks of what the members
 * write rely on. And whose members may wake the user with an expense of their group, see
 * [GroupMemberDao.getUnblockedCurrentMemberUuids], which `push.WakeList` writes down whole.
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

    /**
     * Alice is in both groups, and listed once. Dave is only in the group the user left, which takes
     * no more expenses, and Mallory is blocked.
     */
    @Test
    fun `the members of the groups the user is still in are listed once with nobody blocked`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.identityConflictDao.insert(IdentityConflictEntity(peerUuid = MALLORY))
                database.groupDao.insert(groupOf(GROUP))
                database.groupDao.insert(groupOf(OTHER_GROUP, isLeft = true))
                database.groupDao.insertMembers(listOf(ALICE, BOB, MALLORY).map(::memberOf))
                database.groupDao.insertMembers(
                    listOf(ALICE, DAVE).map { GroupMemberEntity(userUuid = it, groupUuid = OTHER_GROUP, isAdmin = false) },
                )

                assertEquals(
                    listOf(ALICE, BOB),
                    database.groupMemberDao.getUnblockedCurrentMemberUuids().sorted(),
                )
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

            database.groupDao.takeHandedKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 2, NEW_KEY)))

            assertFalse(database.groupDao.isBackedUp())
            assertContentEquals(NEW_KEY, assertNotNull(database.groupDao.get(uuid = GROUP)).groupKey)
        }
    }

    /** Two read in one pass, as for a member who missed a sync. */
    @Test
    fun `the keys handed over in the group are kept with their generations the last one its key`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insertCreated(
                    group = groupOf(GROUP, groupKey = OLD_KEY),
                    members = emptyList(),
                    pendingCreation = PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"),
                )

                database.groupDao.takeHandedKeys(
                    groupUuid = GROUP,
                    keys = listOf(keyOf(generation = 2, NEW_KEY), keyOf(generation = 3, NEWER_KEY)),
                )

                val group = assertNotNull(database.groupDao.get(uuid = GROUP))

                assertContentEquals(NEWER_KEY, group.groupKey)
                assertEquals(3L, group.keyGeneration)
                assertEquals(
                    mapOf(1L to OLD_KEY.toList(), 2L to NEW_KEY.toList(), 3L to NEWER_KEY.toList()),
                    database.groupDao.keysOf(),
                )
            }
        }

    /**
     * The key message of a rotation the server held back and listed after the next one's, which MLS
     * still opens: kept as its generation, and never the group's key in place of the newer one.
     */
    @Test
    fun `a key handed over after a newer one is kept without becoming the group's key`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.upsertWithMembers(
                group = groupOf(GROUP, groupKey = OLD_KEY),
                members = emptyList(),
                olderKeys = emptyList(),
                signatureKeys = emptyList(),
            )

            database.groupDao.takeHandedKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 3, NEWER_KEY)))
            database.groupDao.takeHandedKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 2, NEW_KEY)))

            val group = assertNotNull(database.groupDao.get(uuid = GROUP))

            assertContentEquals(NEWER_KEY, group.groupKey)
            assertEquals(3L, group.keyGeneration)
            assertEquals(
                mapOf(1L to OLD_KEY.toList(), 2L to NEW_KEY.toList(), 3L to NEWER_KEY.toList()),
                database.groupDao.keysOf(),
            )
        }
    }

    /** Both read in one pass, the newer first. */
    @Test
    fun `the keys handed over in one pass make the newest the group's key whatever their order`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP, groupKey = OLD_KEY))

                database.groupDao.takeHandedKeys(
                    groupUuid = GROUP,
                    keys = listOf(keyOf(generation = 3, NEWER_KEY), keyOf(generation = 2, NEW_KEY)),
                )

                val group = assertNotNull(database.groupDao.get(uuid = GROUP))

                assertContentEquals(NEWER_KEY, group.groupKey)
                assertEquals(3L, group.keyGeneration)
            }
        }

    /** What was sealed under the key held of that generation stays readable with it. */
    @Test
    fun `a key handed over for a generation held already does not replace it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.upsertWithMembers(
                group = groupOf(GROUP, groupKey = OLD_KEY),
                members = emptyList(),
                olderKeys = emptyList(),
                signatureKeys = emptyList(),
            )
            database.groupDao.takeHandedKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 2, NEW_KEY)))

            database.groupDao.takeHandedKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 2, NEWER_KEY)))

            assertContentEquals(NEW_KEY, assertNotNull(database.groupDao.get(uuid = GROUP)).groupKey)
            assertEquals(mapOf(1L to OLD_KEY.toList(), 2L to NEW_KEY.toList()), database.groupDao.keysOf())
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

            database.groupDao.adoptRotatedKey(groupUuid = GROUP, groupKey = NEW_KEY, keyGeneration = 2)

            assertTrue(database.groupDao.isBackedUp())
            assertContentEquals(NEW_KEY, assertNotNull(database.groupDao.get(uuid = GROUP)).groupKey)
            assertEquals(2L, assertNotNull(database.groupDao.get(uuid = GROUP)).keyGeneration)
            assertTrue(database.groupDao.getMembershipChanges().isEmpty())
        }
    }

    /** What was sealed under it stays sealed under it, see `group.GroupKeyChain`. */
    @Test
    fun `a key rotation taken keeps the key it replaces as its generation`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insertCreated(
                group = groupOf(GROUP, groupKey = OLD_KEY),
                members = emptyList(),
                pendingCreation = PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"),
            )

            database.groupDao.adoptRotatedKey(groupUuid = GROUP, groupKey = NEW_KEY, keyGeneration = 2)

            assertEquals(mapOf(1L to OLD_KEY.toList(), 2L to NEW_KEY.toList()), database.groupDao.keysOf())
        }
    }

    /** One the creator handed over, which the server cannot have forged, against one a link gave. */
    @Test
    fun `a key got back through the chain never replaces one held`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.upsertWithMembers(
                group = groupOf(GROUP, groupKey = NEWER_KEY, keyGeneration = 3),
                members = emptyList(),
                olderKeys = listOf(keyOf(generation = 2, NEW_KEY)),
                signatureKeys = emptyList(),
            )

            database.groupDao.insertRecoveredKeys(
                groupUuid = GROUP,
                keys = listOf(keyOf(generation = 2, ByteArray(32) { 9 }), keyOf(generation = 1, OLD_KEY)),
            )

            assertEquals(
                mapOf(1L to OLD_KEY.toList(), 2L to NEW_KEY.toList(), 3L to NEWER_KEY.toList()),
                database.groupDao.keysOf(),
            )
        }
    }

    /** Its creation refused while the links were read, say. */
    @Test
    fun `a key got back through the chain for a group no longer held is not kept`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insertRecoveredKeys(groupUuid = GROUP, keys = listOf(keyOf(generation = 1, OLD_KEY)))

            assertTrue(database.groupDao.getKeys(groupUuid = GROUP).isEmpty())
        }
    }

    /** What they wrote while in the group is checked against it, see [GroupMemberSignatureKeyEntity]. */
    @Test
    fun `a member's signature key stays once they are no longer among the members`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.upsertWithMembers(
                group = groupOf(GROUP),
                members = listOf(memberOf(ALICE), memberOf(BOB)),
                olderKeys = emptyList(),
                signatureKeys = listOf(signatureKeyOf(ALICE, OLD_KEY), signatureKeyOf(BOB, NEW_KEY)),
            )

            database.groupDao.replaceMembers(groupUuid = GROUP, members = listOf(memberOf(ALICE)))

            assertContentEquals(NEW_KEY, database.groupDao.getMemberSignatureKey(groupUuid = GROUP, userUuid = BOB))
            assertNull(database.groupDao.getMemberSignatureKey(groupUuid = GROUP, userUuid = DAVE))
        }
    }

    /**
     * Bob left the other group before the user joined it, whose tree never held him on this device:
     * what he wrote there is checked against the key his identity has in every group, as a tree of
     * the same creator's held it.
     */
    @Test
    fun `a member's signature key read in one group is the one they are held to in another of its creator's`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP))
                database.groupDao.insert(groupOf(OTHER_GROUP))
                database.groupDao.upsertSignatureKeys(
                    signatureKeys = listOf(
                        signatureKeyOf(BOB, NEW_KEY),
                        GroupMemberSignatureKeyEntity(groupUuid = OTHER_GROUP, userUuid = DAVE, signatureKey = NEWER_KEY),
                    ),
                )

                assertContentEquals(NEW_KEY, database.groupDao.getMemberSignatureKey(groupUuid = OTHER_GROUP, userUuid = BOB))
                assertContentEquals(NEWER_KEY, database.groupDao.getMemberSignatureKey(groupUuid = OTHER_GROUP, userUuid = DAVE))
            }
        }

    /**
     * Mallory's tree seats a leaf of her own under Bob's UUID, which is only her word: what Bob
     * wrote in a group of Alice's is not held to it.
     */
    @Test
    fun `a member's signature key read in a group of another creator's is not the one they are held to`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP, ownerUuid = MALLORY))
                database.groupDao.insert(groupOf(OTHER_GROUP))
                database.groupDao.upsertSignatureKeys(signatureKeys = listOf(signatureKeyOf(BOB, NEW_KEY)))

                assertNull(database.groupDao.getMemberSignatureKey(groupUuid = OTHER_GROUP, userUuid = BOB))
            }
        }

    /** One read in the group itself comes before one read in another. */
    @Test
    fun `a member's signature key read in the group itself is the one they are held to there`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.groupDao.insert(groupOf(GROUP))
                database.groupDao.insert(groupOf(OTHER_GROUP))
                database.groupDao.upsertSignatureKeys(
                    signatureKeys = listOf(
                        GroupMemberSignatureKeyEntity(groupUuid = OTHER_GROUP, userUuid = BOB, signatureKey = OLD_KEY),
                        signatureKeyOf(BOB, NEW_KEY),
                    ),
                )

                assertContentEquals(NEW_KEY, database.groupDao.getMemberSignatureKey(groupUuid = GROUP, userUuid = BOB))
                assertContentEquals(OLD_KEY, database.groupDao.getMemberSignatureKey(groupUuid = OTHER_GROUP, userUuid = BOB))
            }
        }

    @Test
    fun `a creation refused takes the group's keys and its members' signature keys with it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.groupDao.insertCreated(
                group = groupOf(GROUP, groupKey = OLD_KEY),
                members = listOf(memberOf(ALICE), memberOf(BOB)),
                pendingCreation = PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"),
            )
            database.groupDao.upsertSignatureKeys(signatureKeys = listOf(signatureKeyOf(BOB, NEW_KEY)))

            database.groupDao.deleteRefusedCreation(groupUuid = GROUP)

            assertTrue(database.groupDao.getKeys(groupUuid = GROUP).isEmpty())
            assertNull(database.groupDao.getMemberSignatureKey(groupUuid = GROUP, userUuid = BOB))
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

/** Every generation of the key of the group [GROUP] held, comparable. */
private suspend fun GroupDao.keysOf(): Map<Long, List<Byte>> =
    getKeys(groupUuid = GROUP).associate { it.generation to it.groupKey.toList() }

/** [groupKey] as the generation [generation] of the keys of the group [GROUP]. */
private fun keyOf(generation: Long, groupKey: ByteArray) =
    GroupKeyEntity(groupUuid = GROUP, generation = generation, groupKey = groupKey)

private fun memberOf(userUuid: String) = GroupMemberEntity(userUuid = userUuid, groupUuid = GROUP, isAdmin = userUuid == ALICE)

private fun signatureKeyOf(userUuid: String, signatureKey: ByteArray) =
    GroupMemberSignatureKeyEntity(groupUuid = GROUP, userUuid = userUuid, signatureKey = signatureKey)

/** The UUIDs of the groups owed the key whose partial hash is [CURRENT_HASH]. */
private suspend fun GroupDao.owed(): Set<String> =
    getOwedProfileKey(profileKeyHash = CURRENT_HASH).mapTo(HashSet()) { it.uuid }

/** A group of Alice's, unless [ownerUuid] says otherwise, that the user is in and holds the MLS state of. */
private fun groupOf(
    uuid: String,
    ownerUuid: String = ALICE,
    mlsGroupId: ByteArray? = uuid.encodeToByteArray(),
    groupKey: ByteArray = ByteArray(32),
    keyGeneration: Long = FIRST_KEY_GENERATION,
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
    keyGeneration = keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
