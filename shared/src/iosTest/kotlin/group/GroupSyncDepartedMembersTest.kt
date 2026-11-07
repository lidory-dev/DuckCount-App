package group

import contact.ProfileKeyUpdate
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.applyPendingCommitIn
import mls.commitEpochOf
import mls.encryptApplicationMessage
import mls.inviteeKeyPackage
import mls.removeMembersFrom
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import vault.VaultError
import util.Result
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** When Carol left the group, as the server lists her departure. */
private const val CAROL_LEFT_AT = "2026-10-30T12:00:00.000Z"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What the group syncs of Alice, who created a group with Bob, Carol and Dave, and of Bob make of
 * its members who depart from it, see [GroupDto.departed], and of the Commits the members make:
 * Alice's device removes a departed member at once, Bob's once [DEPARTED_GRACE] has gone by, both
 * through the server's route for them, every member's device takes such a Commit from anybody only
 * when it removes exactly those the server stored it as removing, and a Commit every device
 * refuses is replaced by Alice's. Every change is built and sent by [GroupMembershipChanger] and
 * [GroupMembershipSender] themselves, with every MLS client made through the library, the database
 * stood in for on each device, and the server by [Server], which holds to one Commit per epoch as
 * the API does.
 */
class GroupSyncDepartedMembersTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())
    private val mallory = MlsClient(userUuid = MALLORY, secret = ByteArray(32) { 7 }, store = InMemoryMlsStore())

    private val creation = alice.createGroup(
        groupUuid = GROUP,
        invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage(), dave.inviteeKeyPackage()),
        welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
    )

    /** The key of the epoch the creation leaves the group at, which its information is sealed under. */
    private val firstKey = alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

    private val bobGroupId = joined(bob, others = listOf(ALICE, CAROL, DAVE))
    private val daveGroupId = joined(dave, others = listOf(ALICE, BOB, CAROL))

    private val server = Server()

    /** The time on every device, in milliseconds since the epoch: just after Carol left, unless a test moves it. */
    private var now = millisOf(CAROL_LEFT_AT) + 1_000

    private val aliceDevice = Device(client = alice, userUuid = ALICE, mlsGroupId = creation.mlsGroupId)
    private val bobDevice = Device(client = bob, userUuid = BOB, mlsGroupId = bobGroupId)

    /** On Alice's device, Carol left a moment ago: removed at once, and only Alice's device removes her. */
    @Test
    fun `a member who departed is removed by the creator at once through the departed route`() =
        runBlocking<Unit> {
            server.departs(CAROL)

            val pass = aliceDevice.sync()

            assertNull(pass.failure)
            assertEquals(listOf(CAROL), server.departedRemovals.single().removedMemberUuids)
            assertTrue(server.removals.isEmpty())
            assertEquals(rowsOf(ALICE, BOB, DAVE), aliceDevice.memberRows())
            assertEquals(setOf(ALICE, BOB, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertEquals(2L, aliceDevice.group().keyGeneration)
            assertTrue(aliceDevice.group().isBackedUp)
            assertTrue(aliceDevice.pending.isEmpty())
            assertTrue(server.departed.isEmpty())
        }

    /** Nobody is told: the server cannot tell a member who declined the group from one who left it. */
    @Test
    fun `a member removed for departing is told about to nobody`() = runBlocking<Unit> {
        server.departs(CAROL)

        val pass = aliceDevice.sync()

        assertTrue(pass.joined.isEmpty())
        assertTrue(pass.removed.isEmpty())
    }

    /** A leave and an account deleted since, which the server lists alike. */
    @Test
    fun `every member departed goes in one Commit`() = runBlocking<Unit> {
        server.departs(CAROL)
        server.departs(DAVE)

        assertNull(aliceDevice.sync().failure)

        assertEquals(setOf(CAROL, DAVE), server.departedRemovals.single().removedMemberUuids.toSet())
        assertEquals(rowsOf(ALICE, BOB), aliceDevice.memberRows())
    }

    /** The server still listing her, its answer to the removal lost, say: she is in no tree any more. */
    @Test
    fun `a removal made is not made again`() = runBlocking<Unit> {
        server.departs(CAROL)
        aliceDevice.sync()

        server.departed += DepartedMemberDto(userUuid = CAROL, leftAt = CAROL_LEFT_AT)

        assertNull(aliceDevice.sync().failure)

        assertEquals(1, server.departedRemovals.size)
    }

    @Test
    fun `a group nobody departed is not changed`() = runBlocking<Unit> {
        assertNull(aliceDevice.sync().failure)

        assertTrue(server.departedRemovals.isEmpty())
        assertEquals(1L, alice.rosterOf(mlsGroupId = creation.mlsGroupId).epoch)
    }

    /** In the epoch Bob posted it in: the removal takes the group past it. */
    @Test
    fun `what the members posted is read before the removal`() = runBlocking<Unit> {
        val bobKey = generateSymmetricEncryptionKey()
        val post = bob.encryptApplicationMessage(
            mlsGroupId = bobGroupId,
            payload = ProfileKeyUpdate(profileEncryptionKey = bobKey).serialize(),
        )

        aliceDevice.receive(groupMessageOf(uuid = "post", senderUuid = BOB, message = post))
        server.departs(CAROL)

        assertNull(aliceDevice.sync().failure)

        assertContentEquals(bobKey, aliceDevice.ownerKeys[BOB])
        assertEquals(listOf("post"), aliceDevice.acknowledged)
        assertEquals(1, server.departedRemovals.size)
    }

    /**
     * Past a message that could not be read, a Commit would take the group on and leave it unread
     * for good: the removal waits for the drain to catch up.
     */
    @Test
    fun `a group the drain could not catch up with is changed once it does`() = runBlocking<Unit> {
        server.departs(CAROL)
        aliceDevice.areMessagesReachable = false

        assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), aliceDevice.sync().failure)
        assertTrue(server.departedRemovals.isEmpty())

        aliceDevice.areMessagesReachable = true

        assertNull(aliceDevice.sync().failure)
        assertEquals(1, server.departedRemovals.size)
    }

    /** The creation sent again until the server is heard to take it, which it may have. */
    @Test
    fun `a group whose creation is pending is not changed`() = runBlocking<Unit> {
        aliceDevice.pendingCreations += GROUP
        server.departs(CAROL)

        assertNull(aliceDevice.sync().failure)

        assertTrue(server.departedRemovals.isEmpty())
    }

    /**
     * Sent again, the same removal, at every sync until the server takes it, and none built over
     * it meanwhile: the group is left alone while it is pending.
     */
    @Test
    fun `a removal over the limit is sent again and nothing more is built until it is taken`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            server.answers += GroupMembershipAnswer.RateLimited(retryAfterSeconds = 30)
            server.answers += GroupMembershipAnswer.RateLimited(retryAfterSeconds = 30)

            assertNull(aliceDevice.sync().failure)

            assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, aliceDevice.pending[GROUP]?.kind)
            assertTrue(30L in aliceDevice.resends.seconds)

            assertNull(aliceDevice.sync().failure)
            assertNull(aliceDevice.sync().failure)

            assertEquals(3, server.departedRemovals.size)
            assertEquals(1, server.departedRemovals.map { it.nonce }.toSet().size)
            assertTrue(aliceDevice.pending.isEmpty())
            assertEquals(setOf(ALICE, BOB, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
        }

    /** Refused, the Commit is discarded and the group stays as it was, for the next sync to try again. */
    @Test
    fun `a removal the server refuses fails the sync and leaves the group as it was`() = runBlocking<Unit> {
        server.departs(CAROL)
        server.answers += GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        val pass = aliceDevice.sync()

        assertEquals(GroupSyncError.Membership(GroupMembershipError.Network(NetworkError.BAD_REQUEST)), pass.failure)
        assertTrue(aliceDevice.pending.isEmpty())

        val roster = alice.rosterOf(mlsGroupId = creation.mlsGroupId)

        assertFalse(roster.hasPendingCommit)
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), roster.memberUuids.toSet())
    }

    /** It lists a group to its current members, so this is one the server lost, or the user left. */
    @Test
    fun `a group the server does not list is not changed`() = runBlocking<Unit> {
        server.departs(CAROL)
        server.isListed = false

        assertNull(aliceDevice.sync().failure)

        assertTrue(server.departedRemovals.isEmpty())
    }

    /** A log-out took its MLS state, so it can never be changed from this device again. */
    @Test
    fun `a group brought back after a log-out is not changed`() = runBlocking<Unit> {
        aliceDevice.groups[GROUP] = aliceDevice.group().restored()
        server.departs(CAROL)

        assertNull(aliceDevice.sync().failure)

        assertTrue(server.departedRemovals.isEmpty())
    }

    /** The sync holds the groups lock as it removes them, which is not reentrant. */
    @Test
    fun `removing those who departed does not wait on the lock the sync holds`() = runBlocking<Unit> {
        server.departs(CAROL)

        val pass = assertNotNull(withTimeoutOrNull(timeMillis = 10_000) { aliceDevice.sync() })

        assertNull(pass.failure)
        assertEquals(1, server.departedRemovals.size)
    }

    /**
     * Alice's device offline, Carol left more than [DEPARTED_GRACE] ago: Bob's removes her, and
     * every member's device, Alice's among them, takes it from him, as it removes exactly whom the
     * server stored it as removing.
     */
    @Test
    fun `a member who is not the creator removes one who departed once the grace is over`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

            assertNull(bobDevice.sync().failure)

            val body = server.departedRemovals.single()

            assertEquals(listOf(CAROL), body.removedMemberUuids)
            assertEquals(rowsOf(ALICE, BOB, DAVE), bobDevice.memberRows())
            assertEquals(2L, bobDevice.group().keyGeneration)

            // Alice's device, back online, reads it as every member's does.
            assertNull(aliceDevice.sync().failure)

            assertEquals(setOf(ALICE, BOB, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertContentEquals(bobDevice.group().groupKey, aliceDevice.group().groupKey)
            assertFalse(aliceDevice.group().isAwaitingOwner)
            assertEquals(1, server.departedRemovals.size)
        }

    /**
     * Carol left four minutes ago: the creator's device, woken by her leave, is the one to remove her,
     * and Bob's books a sync for when the grace is over, rounded up to the second.
     */
    @Test
    fun `a member who is not the creator leaves a recent departure to the creator and books a sync for after the grace`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + 4.minutes.inWholeMilliseconds + 500

            assertNull(bobDevice.sync().failure)

            assertTrue(server.departedRemovals.isEmpty())
            assertEquals(listOf((DEPARTED_GRACE - 4.minutes).inWholeSeconds), bobDevice.resends.seconds)

            now += (DEPARTED_GRACE - 4.minutes).inWholeMilliseconds

            assertNull(bobDevice.sync().failure)

            assertEquals(listOf(CAROL), server.departedRemovals.single().removedMemberUuids)
        }

    /** Carol left long ago, Dave a moment ago: Carol goes now, and Dave once his grace is over. */
    @Test
    fun `a member who is not the creator removes the departures due and books a sync for the next`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + 30.minutes.inWholeMilliseconds
            server.departs(DAVE, leftAt = isoOf(now - 2.minutes.inWholeMilliseconds))

            assertNull(bobDevice.sync().failure)

            assertEquals(listOf(CAROL), server.departedRemovals.single().removedMemberUuids)
            assertTrue((DEPARTED_GRACE - 2.minutes).inWholeSeconds in bobDevice.resends.seconds)
        }

    /**
     * Dave's device removed Carol for the very epoch Bob's built its removal in, and the server took
     * Dave's first: Bob's is refused for its epoch, his device reads Dave's, and finds Carol gone.
     */
    @Test
    fun `a removal beaten to its epoch by another member's is read and not made again`() = runBlocking<Unit> {
        server.departs(CAROL)
        now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

        // As the server took it, ahead of Bob's: stored for him, with the departed it removes.
        server.onDepartedRemoval = {
            server.onDepartedRemoval = {}
            daveRemovesDeparted(CAROL)
        }

        assertNull(bobDevice.sync().failure)

        assertEquals(1, server.departedRemovals.size)
        assertEquals(listOf(CAROL), server.departedRemovals.single().removedMemberUuids)
        assertTrue(bobDevice.pending.isEmpty())
        assertEquals(rowsOf(ALICE, BOB, DAVE), bobDevice.memberRows())
        assertEquals(2L, bobDevice.group().keyGeneration)
        assertEquals(server.generation, bobDevice.group().keyGeneration)
        assertFalse(bobDevice.group().isAwaitingOwner)
    }

    /**
     * Bob's removal of Carol beaten to its epoch by a Commit of Dave's that every device refuses:
     * his device reads it, finds itself behind the server, which takes no Commit of that epoch any
     * more, and builds nothing more there until the owner replaced it.
     */
    @Test
    fun `a removal beaten to its epoch by a Commit every device refuses is not made again`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

            server.onDepartedRemoval = {
                server.onDepartedRemoval = {}
                server.stores(commit = dave.addMembersTo(mallory), senderUuid = DAVE, departedMemberUuids = emptyList())
            }

            assertNull(bobDevice.sync().failure)

            assertEquals(1, server.departedRemovals.size)
            assertTrue(bobDevice.pending.isEmpty())
            assertTrue(bobDevice.group().isAwaitingOwner)
        }

    /**
     * Dave removes Carol, who never departed, and the server stores it as removing nobody departed:
     * Bob's device refuses it whatever its own listing says of Carol, the Commit being held to the
     * server's word for that one message, and waits for the owner.
     */
    @Test
    fun `a Commit by a member is held to the departed the server stored it with and not to the listing`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + 1_000

            val commit = dave.removeMembersFrom(mlsGroupId = daveGroupId, userUuids = listOf(CAROL))

            server.stores(commit = commit, senderUuid = DAVE, departedMemberUuids = emptyList())

            assertNull(bobDevice.sync().failure)

            assertEquals(setOf(ALICE, BOB, CAROL, DAVE), bob.rosterOf(mlsGroupId = bobGroupId).memberUuids.toSet())
            assertEquals(FIRST_KEY_GENERATION, bobDevice.group().keyGeneration)
            assertTrue(bobDevice.group().isAwaitingOwner)
            assertTrue(server.departedRemovals.isEmpty())
        }

    /** Removing more than the server stored it as removing is no departed removal either. */
    @Test
    fun `a Commit by a member removing more than those departed is refused`() = runBlocking<Unit> {
        server.departs(CAROL)

        val commit = dave.removeMembersFrom(mlsGroupId = daveGroupId, userUuids = listOf(CAROL, BOB))

        server.stores(commit = commit, senderUuid = DAVE, departedMemberUuids = listOf(CAROL))

        assertNull(aliceDevice.sync().failure)

        // Refused there, and replaced by Alice's own removal of Dave and of Carol, who departed.
        val replacement = server.replacements.single()

        assertEquals(FIRST_KEY_GENERATION, replacement.replacesEpoch)
        assertEquals(setOf(CAROL, DAVE), replacement.removedMemberUuids.toSet())
    }

    /**
     * Bob, no creator, added Mallory, and the server stored it: every member's device refuses it,
     * Alice's too, and stays in the epoch before. Hers, the creator's, replaces it with a Commit of
     * her own for that epoch, removing whoever sent the refused one, which every other member's
     * device then takes, back in step with the server.
     */
    @Test
    fun `a Commit every device refuses is replaced by the creator's removing whoever sent it`() =
        runBlocking<Unit> {
            val forged = bob.addMembersTo(mallory)

            server.stores(commit = forged, senderUuid = BOB, departedMemberUuids = emptyList())

            assertNull(aliceDevice.sync().failure)

            val replacement = server.replacements.single()

            assertEquals(FIRST_KEY_GENERATION, replacement.replacesEpoch)
            assertEquals(listOf(BOB), replacement.removedMemberUuids)
            assertEquals(2L, server.generation)
            assertEquals(2L, aliceDevice.group().keyGeneration)
            assertFalse(aliceDevice.group().isAwaitingOwner)
            assertEquals(setOf(ALICE, CAROL, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())

            // Acknowledged once the replacement went out, which names its sender.
            assertEquals(listOf(server.messageUuidOf(forged)), aliceDevice.acknowledged)

            // Dave's device refused it too, and takes Alice's.
            val read = dave.processUnderPolicy(
                mlsGroupId = daveGroupId,
                message = Base64.Default.decode(replacement.commitMessage),
                groupUuid = GROUP,
                creatorUuid = ALICE,
            )

            assertEquals(ALICE, (read.processed as ProcessedMessage.Commit).committerUserUuid)
            assertEquals(setOf(ALICE, CAROL, DAVE), dave.rosterOf(mlsGroupId = daveGroupId).memberUuids.toSet())
            assertContentEquals(aliceDevice.group().groupKey, dave.contentKeyIn(mlsGroupId = daveGroupId, groupUuid = GROUP))
        }

    /**
     * The device locking as the replacement reads the key ring: the refused Commit, which says whom
     * to remove, stays unacknowledged, and the next pass replaces it then.
     */
    @Test
    fun `a Commit refused on a device that locks before its replacement is read again by the next pass`() =
        runBlocking<Unit> {
            val forged = bob.addMembersTo(mallory)

            server.stores(commit = forged, senderUuid = BOB, departedMemberUuids = emptyList())
            aliceDevice.keyCopies.isDeviceLocked = true

            assertEquals(GroupSyncError.DeviceLocked, aliceDevice.sync().failure)

            assertTrue(server.replacements.isEmpty())
            assertTrue(aliceDevice.acknowledged.isEmpty())

            aliceDevice.keyCopies.isDeviceLocked = false

            assertNull(aliceDevice.sync().failure)

            assertEquals(listOf(BOB), server.replacements.single().removedMemberUuids)
            assertEquals(listOf(server.messageUuidOf(forged)), aliceDevice.acknowledged)
        }

    /**
     * The key ring not answering as the replacement reads it, nothing saved: the refused Commit,
     * the one record of whom to remove, stays unacknowledged, and the next pass, reading it again,
     * removes its sender.
     */
    @Test
    fun `a Commit refused whose replacement could not be made is read again by the next pass`() =
        runBlocking<Unit> {
            val forged = bob.addMembersTo(mallory)

            server.stores(commit = forged, senderUuid = BOB, departedMemberUuids = emptyList())
            aliceDevice.keyCopies.isKeyRingAnswering = false

            assertEquals(
                GroupSyncError.Membership(GroupMembershipError.Vault(VaultError.Network(NetworkError.UNREACHABLE))),
                aliceDevice.sync().failure,
            )

            assertTrue(server.replacements.isEmpty())
            assertTrue(aliceDevice.acknowledged.isEmpty())

            aliceDevice.keyCopies.isKeyRingAnswering = true

            assertNull(aliceDevice.sync().failure)

            assertEquals(listOf(BOB), server.replacements.single().removedMemberUuids)
            assertEquals(listOf(server.messageUuidOf(forged)), aliceDevice.acknowledged)
        }

    /** Refused by the server, which would refuse it again: the Commit it replaced is done with. */
    @Test
    fun `a Commit refused whose replacement the server refuses is acknowledged`() = runBlocking<Unit> {
        val forged = bob.addMembersTo(mallory)

        server.stores(commit = forged, senderUuid = BOB, departedMemberUuids = emptyList())
        server.answers += GroupMembershipAnswer.Refused(error = NetworkError.CONFLICT)

        assertEquals(
            GroupSyncError.Membership(GroupMembershipError.Network(NetworkError.CONFLICT)),
            aliceDevice.sync().failure,
        )

        assertEquals(listOf(server.messageUuidOf(forged)), aliceDevice.acknowledged)
    }

    /**
     * Bob's device waiting for its owner, behind the server: no Commit of the epoch it is at could
     * be taken, so Carol departing has it remove nobody, nor book anything, until it is in step.
     */
    @Test
    fun `a member's device waiting for its owner removes nobody who departs meanwhile`() = runBlocking<Unit> {
        server.stores(commit = dave.addMembersTo(mallory), senderUuid = DAVE, departedMemberUuids = emptyList())

        assertNull(bobDevice.sync().failure)

        server.departs(CAROL)
        now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

        assertNull(bobDevice.sync().failure)

        assertTrue(bobDevice.group().isAwaitingOwner)
        assertTrue(server.departedRemovals.isEmpty())
        assertTrue(bobDevice.resends.seconds.isEmpty())

        // Alice's replacement removes Dave and Carol, who departed, and takes Bob's device back in step.
        assertNull(aliceDevice.sync().failure)
        assertNull(bobDevice.sync().failure)

        assertFalse(bobDevice.group().isAwaitingOwner)
        assertEquals(setOf(ALICE, BOB), bob.rosterOf(mlsGroupId = bobGroupId).memberUuids.toSet())
        assertTrue(server.departedRemovals.isEmpty())
    }

    /**
     * Alice's device behind the server with nothing left to read, what refused there acknowledged
     * long ago: it replaces whatever the server holds past it, removing those departed.
     */
    @Test
    fun `the creator's device behind the server with nothing to read replaces with those departed`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            server.generation = 2

            assertNull(aliceDevice.sync().failure)

            val replacement = server.replacements.single()

            assertEquals(FIRST_KEY_GENERATION, replacement.replacesEpoch)
            assertEquals(listOf(CAROL), replacement.removedMemberUuids)
            assertTrue(server.departedRemovals.isEmpty())
        }

    /**
     * Bob's device behind the server with nothing left to read: it cannot write in the group, and
     * waits for its owner, whose replacement takes it back in step.
     */
    @Test
    fun `a member's device behind the server with nothing to read waits for the owner until in step`() =
        runBlocking<Unit> {
            val forged = dave.addMembersTo(mallory)

            server.stores(commit = forged, senderUuid = DAVE, departedMemberUuids = emptyList())

            assertNull(bobDevice.sync().failure)

            assertTrue(bobDevice.group().isAwaitingOwner)
            assertTrue(server.replacements.isEmpty())

            // Nothing left to read, still behind.
            assertNull(bobDevice.sync().failure)

            assertTrue(bobDevice.group().isAwaitingOwner)

            assertNull(aliceDevice.sync().failure)
            assertNull(bobDevice.sync().failure)

            assertFalse(bobDevice.group().isAwaitingOwner)
            assertEquals(server.generation, bobDevice.group().keyGeneration)
            assertEquals(setOf(ALICE, BOB, CAROL), bob.rosterOf(mlsGroupId = bobGroupId).memberUuids.toSet())
        }

    /**
     * Bob's removal of Carol not heard to be taken, and Alice removing him while it waits: the server
     * answers its resend with a `404` for good, and lists the group to Bob no more. Taken by nobody,
     * as the first Commit waiting for him, Alice's, built in the epoch his was, says, it is
     * discarded, and the group read up to his removal.
     */
    @Test
    fun `a removal pending from a member removed since is discarded once the group is no longer listed`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

            assertNull(bobDevice.sync().failure)
            assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, bobDevice.pending[GROUP]?.kind)

            aliceRemoves(BOB, CAROL)

            val pass = bobDevice.sync()

            assertTrue(bobDevice.pending.isEmpty())
            assertTrue(bobDevice.group().isLeft)
            assertEquals(ALICE, pass.removed.single().removerUuid)
            assertNull(bobDevice.sync().failure)

            // Sent once, again once, and never after.
            assertEquals(2, server.departedRemovals.size)
        }

    /**
     * Bob's removal of Carol taken, its answer lost, and Alice, reading it, removing him after it:
     * the first Commit waiting for him, Alice's, is built in the epoch his leads to, so his is
     * applied, and the group read up to his removal.
     */
    @Test
    fun `a removal pending from a member removed since is applied once the group is no longer listed`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000
            server.losesAnswer = true

            assertNull(bobDevice.sync().failure)
            assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, bobDevice.pending[GROUP]?.kind)
            assertNull(aliceDevice.sync().failure)

            aliceRemoves(BOB)

            val pass = bobDevice.sync()

            assertTrue(bobDevice.pending.isEmpty())
            assertTrue(bobDevice.group().isLeft)
            assertEquals(ALICE, pass.removed.single().removerUuid)
            assertEquals(3L, server.generation)
        }

    /**
     * Bob's device read Alice's removal of Dave, its acknowledgement lost, and removed Carol in the
     * same pass, taken with its answer lost, before Alice removed him. Alice's removal of Dave
     * still waits for him, ahead of the rest, built in the epoch before his: past it, the first
     * Commit waiting is hers removing him, built in the epoch his leads to, so his is applied, and
     * the group read up to his removal.
     */
    @Test
    fun `a removal pending from a member removed since is applied past an earlier Commit left unacknowledged`() =
        runBlocking<Unit> {
            server.departs(CAROL)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000
            aliceRemoves(DAVE)
            bobDevice.isAcknowledging = false
            server.losesAnswer = true

            assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), bobDevice.sync().failure)
            assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, bobDevice.pending[GROUP]?.kind)

            bobDevice.isAcknowledging = true

            assertNull(aliceDevice.sync().failure)

            aliceRemoves(BOB)

            val pass = bobDevice.sync()

            assertTrue(bobDevice.pending.isEmpty())
            assertTrue(bobDevice.group().isLeft)
            assertEquals(ALICE, pass.removed.single().removerUuid)
        }

    /**
     * Alice's removal of Carol, who never departed, not heard to be taken, and Bob's device removing
     * Dave, who did, as it waits: sent again, it is refused for its epoch, and made anew over Bob's
     * once Alice's device read it, as she asked, rather than dropped.
     */
    @Test
    fun `a removal the user asked for refused for its epoch as it is sent again is made anew`() =
        runBlocking<Unit> {
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

            assertEquals(Result.Success(MembershipChangeOutcome.PENDING), aliceDevice.remove(CAROL))

            server.departs(DAVE)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

            assertNull(bobDevice.sync().failure)
            assertEquals(listOf(DAVE), server.departedRemovals.single().removedMemberUuids)

            assertNull(aliceDevice.sync().failure)

            assertEquals(3, server.removals.size)
            assertEquals(listOf(CAROL), server.removals.last().removedMemberUuids)
            assertEquals(setOf(ALICE, BOB), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertTrue(aliceDevice.pending.isEmpty())
            assertTrue(aliceDevice.outdated.isEmpty())
            assertEquals(3L, server.generation)
            assertEquals(3L, aliceDevice.group().keyGeneration)
        }

    /** Made anew and not heard to be taken either: pending, as any change, from the epoch Bob's led to. */
    @Test
    fun `a removal the user asked for made anew and not heard to be taken stays pending`() = runBlocking<Unit> {
        server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        aliceDevice.remove(CAROL)

        server.departs(DAVE)
        now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000
        bobDevice.sync()
        server.afterRemoval = {
            server.afterRemoval = {}
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)
        }

        assertNull(aliceDevice.sync().failure)

        val pending = assertNotNull(aliceDevice.pending[GROUP])

        assertEquals(MembershipChangeKind.REMOVAL, pending.kind)
        assertFalse(pending.isOutdated)
        assertEquals(2L, pending.commitEpoch)
        assertTrue(aliceDevice.outdated.isEmpty())
    }

    /** Beaten to its epoch once more as it is made anew: kept to be made again, by the next sync. */
    @Test
    fun `a removal the user asked for made anew and refused for its epoch again is made by the next sync`() =
        runBlocking<Unit> {
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

            aliceDevice.remove(CAROL)

            server.departs(DAVE)
            now = millisOf(CAROL_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000
            bobDevice.sync()
            server.afterRemoval = {
                server.afterRemoval = {}
                server.answers += GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 3)
            }

            assertEquals(GroupSyncError.Membership(GroupMembershipError.Outdated), aliceDevice.sync().failure)

            assertEquals(MembershipChangeKind.REMOVAL, aliceDevice.outdated[GROUP]?.kind)
            assertTrue(aliceDevice.pending.isEmpty())
            assertEquals(setOf(ALICE, BOB, CAROL), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())

            assertNull(aliceDevice.sync().failure)

            assertTrue(aliceDevice.pending.isEmpty())
            assertTrue(aliceDevice.outdated.isEmpty())
            assertEquals(listOf(CAROL), server.removals.last().removedMemberUuids)
            assertEquals(setOf(ALICE, BOB), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
        }

    /**
     * Alice's removal of Carol not heard to be taken, and a Commit of Bob's that every device
     * refuses stored meanwhile: sent again, the removal is refused for its epoch, and Alice's device
     * replaces Bob's Commit all the same, then makes her removal anew over it, as she asked.
     */
    @Test
    fun `a Commit every device refuses is replaced ahead of a change the user asked for to be made again`() =
        runBlocking<Unit> {
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

            assertEquals(Result.Success(MembershipChangeOutcome.PENDING), aliceDevice.remove(CAROL))

            server.stores(commit = bob.addMembersTo(mallory), senderUuid = BOB, departedMemberUuids = emptyList())

            assertNull(aliceDevice.sync().failure)

            assertEquals(listOf(BOB), server.replacements.single().removedMemberUuids)
            assertEquals(listOf(CAROL), server.removals.last().removedMemberUuids)
            assertEquals(setOf(ALICE, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertTrue(aliceDevice.pending.isEmpty())
            assertTrue(aliceDevice.outdated.isEmpty())
            assertEquals(3L, server.generation)
            assertEquals(3L, aliceDevice.group().keyGeneration)
        }

    /**
     * The replacement not heard to be taken either: Alice's removal of Carol waits beside it, and is
     * made by the sync that hears the server took it.
     */
    @Test
    fun `a change the user asked for to be made again waits beside a replacement not heard to be taken`() =
        runBlocking<Unit> {
            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

            aliceDevice.remove(CAROL)

            server.stores(commit = bob.addMembersTo(mallory), senderUuid = BOB, departedMemberUuids = emptyList())
            server.afterRemoval = {
                server.afterRemoval = {}
                server.answers += GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)
            }

            assertNull(aliceDevice.sync().failure)

            assertEquals(MembershipChangeKind.REPLACEMENT, aliceDevice.pending[GROUP]?.kind)
            assertEquals(MembershipChangeKind.REMOVAL, aliceDevice.outdated[GROUP]?.kind)

            assertNull(aliceDevice.sync().failure)

            assertTrue(aliceDevice.pending.isEmpty())
            assertTrue(aliceDevice.outdated.isEmpty())
            assertEquals(listOf(BOB), server.replacements.last().removedMemberUuids)
            assertEquals(listOf(CAROL), server.removals.last().removedMemberUuids)
            assertEquals(setOf(ALICE, DAVE), alice.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertEquals(3L, server.generation)
        }

    /** Has Alice's device remove [userUuids], who did not depart, by hand, and the server take it. */
    private suspend fun aliceRemoves(vararg userUuids: String) {
        val commit = alice.removeMembersFrom(mlsGroupId = creation.mlsGroupId, userUuids = userUuids.toList())

        alice.applyPendingCommitIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)
        server.stores(commit = commit, senderUuid = ALICE, departedMemberUuids = emptyList())
        server.members -= userUuids.toSet()
        server.departed.removeAll { it.userUuid in userUuids }
    }

    /** Has [client] join Alice's group from its Welcome, beside [others], and returns their copy of it. */
    private fun joined(client: MlsClient, others: List<String>): ByteArray = client.joinGroup(
        welcome = creation.welcome,
        expectedGroupUuid = GROUP,
        expectedMembers = others,
        pinMembers = false,
    )

    /** Has Dave's device remove [userUuid], who departed, and the server take it, ahead of anybody's. */
    private suspend fun daveRemovesDeparted(userUuid: String) {
        val commit = dave.removeMembersFrom(mlsGroupId = daveGroupId, userUuids = listOf(userUuid))

        dave.applyPendingCommitIn(mlsGroupId = daveGroupId, groupUuid = GROUP)
        server.stores(commit = commit, senderUuid = DAVE, departedMemberUuids = listOf(userUuid))
        server.departed.removeAll { it.userUuid == userUuid }
        server.members -= userUuid
    }

    /** The Commit adding [invitee] built on this client's device, which no member's device takes but the creator's. */
    private fun MlsClient.addMembersTo(invitee: MlsClient): ByteArray {
        val mlsGroupId = if (this == bob) bobGroupId else daveGroupId

        return loadGroup(mlsGroupId = mlsGroupId).use { group ->
            group.addMembers(invitees = listOf(invitee.inviteeKeyPackage()), welcomePayload = null).commit
        }
    }

    /**
     * The server as the API holds a group: its current members, those departed, its epoch, and the
     * messages it stores for each member, every Commit for the epoch it is at alone, see
     * [GroupMembershipAnswer.KeyGenerationNotCurrent].
     */
    private inner class Server {
        val members = mutableListOf(ALICE, BOB, CAROL, DAVE)
        val departed = mutableListOf<DepartedMemberDto>()
        var generation = FIRST_KEY_GENERATION
        var isListed = true

        /** What the server answers a Commit next, one after the other, before taking it. */
        val answers = mutableListOf<GroupMembershipAnswer>()

        /** What runs as a departed removal comes in, before it is held to the epoch. */
        var onDepartedRemoval: suspend () -> Unit = {}

        /** Whether the answer to the next departed removal the server takes is lost on the way back. */
        var losesAnswer = false

        val removals = mutableListOf<RemoveGroupMembersBody>()
        val departedRemovals = mutableListOf<RemoveDepartedMembersBody>()
        val replacements = mutableListOf<ReplaceGroupCommitBody>()

        private val nonces = mutableSetOf<String>()
        private val messages = mutableListOf<Pair<String, ByteArray>>()
        private val devices = mutableListOf<Device>()
        private var messageCount = 0

        fun register(device: Device) {
            devices += device
        }

        /** Has [userUuid] leave, or delete their account, at [leftAt]. */
        fun departs(userUuid: String, leftAt: String = CAROL_LEFT_AT) {
            members -= userUuid
            departed += DepartedMemberDto(userUuid = userUuid, leftAt = leftAt)
        }

        /** The UUID of the message the server stored [commit] in. */
        fun messageUuidOf(commit: ByteArray): String = messages.first { it.second contentEquals commit }.first

        /**
         * Stores [commit] from [senderUuid] for every current member but them, as removing
         * [departedMemberUuids] for having departed, and takes the group one epoch further.
         */
        fun stores(commit: ByteArray, senderUuid: String, departedMemberUuids: List<String>) {
            val uuid = "m${++messageCount}"

            messages += uuid to commit
            generation += 1

            devices.filter { it.userUuid != senderUuid && it.userUuid in members }.forEach { device ->
                device.receive(
                    groupMessageOf(
                        uuid = uuid,
                        senderUuid = senderUuid,
                        message = commit,
                        departedMemberUuids = departedMemberUuids,
                    ),
                )
            }
        }

        /**
         * A departed removal from [senderUuid]: refused with a `404` to one no longer a member, ahead
         * of everything, as the API does, whether or not it took it before.
         */
        suspend fun removeDeparted(senderUuid: String, body: RemoveDepartedMembersBody): GroupMembershipAnswer {
            departedRemovals += body
            onDepartedRemoval()

            if (senderUuid !in members) return GroupMembershipAnswer.Failed(error = NetworkError.NOT_FOUND)

            val answer = taken(nonce = body.nonce, commitMessage = body.commitMessage) {
                if (!body.removedMemberUuids.all { uuid -> departed.any { it.userUuid == uuid } }) {
                    return@taken GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)
                }

                stores(
                    commit = Base64.Default.decode(body.commitMessage),
                    senderUuid = senderUuid,
                    departedMemberUuids = body.removedMemberUuids,
                )
                departed.removeAll { it.userUuid in body.removedMemberUuids }

                GroupMembershipAnswer.Made
            }

            if (answer == GroupMembershipAnswer.Made && losesAnswer) {
                losesAnswer = false

                return GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)
            }

            return answer
        }

        /** What runs once a removal by hand is answered. */
        var afterRemoval: () -> Unit = {}

        /** Alice's removal of members by hand, for every current member, those it removes included. */
        suspend fun remove(body: RemoveGroupMembersBody): GroupMembershipAnswer {
            removals += body

            return taken(nonce = body.nonce, commitMessage = body.commitMessage) {
                stores(
                    commit = Base64.Default.decode(body.commitMessage),
                    senderUuid = ALICE,
                    departedMemberUuids = emptyList(),
                )
                members -= body.removedMemberUuids.toSet()

                GroupMembershipAnswer.Made
            }.also { afterRemoval() }
        }

        /**
         * The owner's replacement: every Commit from the epoch it names on is undone, and its own
         * stored in their place, for the epoch after it.
         */
        suspend fun replace(body: ReplaceGroupCommitBody): GroupMembershipAnswer {
            replacements += body

            answers.removeFirstOrNull()?.let { return it }

            generation = body.replacesEpoch
            members -= body.removedMemberUuids.toSet()

            return taken(nonce = body.nonce, commitMessage = body.commitMessage) {
                stores(
                    commit = Base64.Default.decode(body.commitMessage),
                    senderUuid = ALICE,
                    departedMemberUuids = emptyList(),
                )
                departed.removeAll { it.userUuid in body.removedMemberUuids }

                GroupMembershipAnswer.Made
            }
        }

        /**
         * What the server answers a Commit, [commitMessage], named by [nonce]: a resend's `409` ahead of
         * everything, the JSON `409` naming its epoch when it is not built in the group's, else what
         * [take] makes of it, unless a test has it answer otherwise first.
         */
        private suspend fun taken(
            nonce: String,
            commitMessage: String,
            take: suspend () -> GroupMembershipAnswer,
        ): GroupMembershipAnswer {
            if (nonce in nonces) return GroupMembershipAnswer.AlreadyMade

            answers.removeFirstOrNull()?.let { return it }

            if (commitEpochOf(Base64.Default.decode(commitMessage)) != generation) {
                return GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = generation)
            }

            return take().also { answer -> if (answer == GroupMembershipAnswer.Made) nonces += nonce }
        }

        /** The group as the server lists it to its current members. */
        fun listing(userUuid: String): List<GroupDto> {
            if (!isListed || userUuid !in members) return emptyList()

            return listOf(
                listedGroupOf(
                    uuid = GROUP,
                    ownerUuid = ALICE,
                    version = FIELDS.sealedAs(creator = alice, groupKey = firstKey, groupUuid = GROUP),
                    memberUuids = members.toList(),
                    keyGeneration = generation,
                    departed = departed.toList(),
                ),
            )
        }
    }

    /**
     * One member's device, holding the group as joined, its MLS state in [mlsGroupId], with the
     * group sync, changer and sender every device runs.
     */
    private inner class Device(val client: MlsClient, val userUuid: String, mlsGroupId: ByteArray) {
        val groups = mutableMapOf(GROUP to heldGroup(mlsGroupId = mlsGroupId))
        val members = mutableMapOf(GROUP to rowsOf(ALICE, BOB, CAROL, DAVE).toList())
        /** The changes waiting on the server, and those to be made again, see [PendingMembershipChangeEntity.isOutdated]. */
        val pending = mutableMapOf<String, PendingMembershipChangeEntity>()
        val outdated = mutableMapOf<String, PendingMembershipChangeEntity>()
        val pendingCreations = mutableSetOf<String>()
        val ownerKeys = mutableMapOf<String, ByteArray>()
        val resends = ResendsBooked()
        val keyCopies = ServerKeyCopies()

        /**
         * What waits for the device, whether it can be asked for, whether an acknowledgement goes
         * through, and what it acknowledged, in order.
         */
        private val waiting = mutableListOf<GroupMessageDto>()
        var areMessagesReachable = true
        var isAcknowledging = true
        val acknowledged = mutableListOf<String>()

        init {
            server.register(this)
        }

        private val sender = GroupMembershipSender(
            addMembers = { _, _ -> error("Nobody adds anybody here.") },
            removeMembers = { _, body -> server.remove(body = body) },
            removeDeparted = { _, body -> server.removeDeparted(senderUuid = userUuid, body = body) },
            replaceCommit = { _, body -> server.replace(body = body) },
            rejoin = { _, _ -> error("Nobody rejoins anything here.") },
            group = { groups[it] },
            listPending = { pending.values + outdated.values },
            dropPending = { pending.remove(it) },
            markOutdated = { groupUuid ->
                outdated[groupUuid] = checkNotNull(pending.remove(groupUuid)).copy(isOutdated = true)
            },
            dropOutdated = { outdated.remove(it) },
            forgetRejoin = { error("Nobody rejoins anything here.") },
            replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
            recordSignatureKeys = {},
            rebuildWakeList = {},
            takeCommittedKey = { key ->
                groups[key.groupUuid] = groups.getValue(key.groupUuid).with(key = key, isBackedUp = true)
                pending.remove(key.groupUuid)
            },
            client = { Result.Success(client) },
            receiveAccountDeletions = {},
            resendScheduler = resends,
        )

        private val changer = GroupMembershipChanger(
            userUuid = { userUuid },
            restoreContacts = { Result.Success(Unit) },
            contacts = { listOf(ALICE, BOB, CAROL, DAVE) - userUuid },
            blocked = { emptyList() },
            client = { Result.Success(client) },
            keyCopySealer = keyCopies::readSealer,
            consumeKeyPackage = { error("Nobody adds anybody here.") },
            group = { groups[it] },
            hasPendingChange = { it in pending || it in outdated || it in pendingCreations },
            savePending = { change -> pending[change.groupUuid] = change },
            outdatedChanges = { outdated.values.toList() },
            dropOutdated = { outdated.remove(it) },
            bookResend = sender::bookResend,
            send = sender::send,
            ownProfileKey = { null },
            contactProfileKey = { null },
            learnedProfileKey = { null },
            oweProfileKey = {},
            syncGroups = { error("The sync's own changes never sync.") },
        )

        private val groupSync = GroupSync(
            userUuid = { userUuid },
            resendPending = { Result.Success(Unit) },
            resendMembershipChanges = sender::resendPending,
            // What becomes of the copies of the group keys is GroupKeyCopiesTest's.
            openKeyCopies = { Result.Success(emptyMap()) },
            putUpKeyCopies = { Result.Success(Unit) },
            restoreContacts = { Result.Success(Unit) },
            listGroups = { Result.Success(server.listing(userUuid = userUuid)) },
            storedGroups = { groups.values.toList() },
            declinedGroups = { emptyList() },
            listMessages = {
                if (areMessagesReachable) {
                    Result.Success(ListGroupMessagesResponse(messages = waiting.toList()))
                } else {
                    Result.Error(NetworkError.NO_INTERNET)
                }
            },
            acknowledge = { _, messageUuids ->
                if (isAcknowledging) {
                    acknowledged += messageUuids
                    waiting.removeAll { it.uuid in messageUuids }
                    Result.Success(Unit)
                } else {
                    Result.Error(NetworkError.NO_INTERNET)
                }
            },
            leave = { error("Nobody declines anything here.") },
            keyLinks = { Result.Success(emptyList()) },
            isUnblockedContact = { true },
            client = { Result.Success(client) },
            save = { _, _, _, _ -> error("Nobody joins anything here.") },
            memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
            replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
            recordSignatureKeys = {},
            takeKeys = { groupUuid, keys ->
                groups[groupUuid] = groups.getValue(groupUuid).with(key = keys.maxBy { it.generation }, isBackedUp = false)
            },
            updateInformation = { _, _ -> error("Nobody edits anything here.") },
            markLeft = { groupUuid -> groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true) },
            recordDeclined = { error("Nobody declines anything here.") },
            forgetDeclined = { error("Nobody declines anything here.") },
            setAwaitingOwner = { groupUuid, isAwaitingOwner ->
                groups[groupUuid] = groups.getValue(groupUuid).with(isAwaitingOwner = isAwaitingOwner)
            },
            removeDeparted = changer::removeDepartedUnderGroupsLock,
            replaceRefused = changer::replaceUnderGroupsLock,
            remakeOutdated = changer::remakeOutdatedUnderGroupsLock,
            settleUnlisted = sender::settleUnlisted,
            keyOf = { _, _, _ -> error("Nobody edits anything here.") },
            checkKeyLinks = { Result.Success(Unit) },
            // What becomes of a group brought back after a log-out is GroupSyncRejoinTest's.
            requestRejoin = { Result.Success(Unit) },
            rejoinRestored = { _, _ -> GroupRejoinPass(pending = emptySet(), unanswered = emptySet(), failure = null) },
            publishGroupInfos = { _, _ -> Result.Success(Unit) },
            fullName = { null },
            fullNameEvenIfHidden = { null },
            hasPendingChange = { it in pending || it in pendingCreations },
            hasPendingCreation = { false },
            markDeleted = { error("Nobody deletes a group here.") },
            isBlocked = { false },
            setHandedProfileKey = { _, _ -> },
            takeOwnerKey = { owner, profileEncryptionKey, _ -> ownerKeys[owner] = profileEncryptionKey },
            fillGap = { _, _ -> },
            restoreMemberProfiles = { Result.Success(Unit) },
            readMemberNames = { Result.Success(Unit) },
            backUpMemberProfiles = { Result.Success(Unit) },
            handOutProfileKey = { Result.Success(Unit) },
            rebuildWakeList = {},
            now = { now },
            resendScheduler = resends,
        )

        suspend fun sync() = groupSync.sync(contactsTakenIn = true)

        /** Has the user remove [userUuid] from the group by hand, as its settings do. */
        suspend fun remove(userUuid: String) = changer.remove(groupUuid = GROUP, userUuids = setOf(userUuid))

        fun receive(message: GroupMessageDto) {
            waiting += message
        }

        fun group() = groups.getValue(GROUP)

        fun memberRows() = members.getValue(GROUP).toSet()

        /** The group as this device holds it once joined, at the epoch the creation left it at. */
        private fun heldGroup(mlsGroupId: ByteArray) = GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = ALICE,
            mlsGroupId = mlsGroupId,
            groupKey = firstKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        )
    }
}

private fun rowsOf(vararg userUuids: String) =
    userUuids.mapTo(HashSet()) { GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) }

/** When [iso], RFC 3339 in UTC to the millisecond, is, in milliseconds since the epoch. */
@OptIn(ExperimentalTime::class)
private fun millisOf(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

/** [millis] since the epoch, RFC 3339 in UTC to the millisecond, as the API writes it. */
@OptIn(ExperimentalTime::class)
private fun isoOf(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

private fun GroupEntity.with(
    key: GroupKeyEntity? = null,
    isBackedUp: Boolean = this.isBackedUp,
    isAwaitingOwner: Boolean = this.isAwaitingOwner,
    isLeft: Boolean = this.isLeft,
) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = key?.groupKey ?: groupKey,
    keyGeneration = key?.generation ?: keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
    isAwaitingOwner = isAwaitingOwner,
)

/** [this] as a log-out and the group sync bringing it back leave it. */
private fun GroupEntity.restored() = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = null,
    groupKey = groupKey,
    keyGeneration = keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)

