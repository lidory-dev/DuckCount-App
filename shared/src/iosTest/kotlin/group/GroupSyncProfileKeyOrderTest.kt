package group

import contact.ProfileKeyUpdate
import database.data.local.dao.MemberProfileDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.addMembersTo
import mls.applyPendingCommitIn
import mls.encryptApplicationMessage
import mls.inviteeKeyPackage
import mls.removeMembersFrom
import profile.ProfileError
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import vault.InMemoryMemberProfileVault
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

private const val FIRST_GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val SECOND_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** What both groups are created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * Which of a member's keys Carol's device keeps when it reads two of them out of two groups in the
 * order that used to let the older one win. Alice created both groups, with Bob and Carol in each,
 * who are not each other's contacts: Alice is Carol's, pinned. Bob posts his key into each as he
 * joins, then his key rotates, and Alice removes him from the first group before he posts the new
 * one there, so it goes into the second alone. Carol is offline all along, and her device reads
 * the second group first, as the database may give them, or the first in a later pass, the server
 * having failed to list it in the pass that read the second. Alice's own key, which goes with each
 * of her Welcomes and with each map of the keys she hands over as she seats Dave, is ranked the
 * same way.
 *
 * Every MLS client is the library's own, the server delivers what waits as the API does, stamping
 * each message a second after the one before it, and the keys land in the database itself, see
 * [withInMemoryDatabase].
 */
@OptIn(ExperimentalTime::class)
class GroupSyncProfileKeyOrderTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    /** The key to each one's profile, and the one Bob's rotates to. */
    private val aliceKey = generateSymmetricEncryptionKey()
    private val bobKey = generateSymmetricEncryptionKey()
    private val bobNewKey = generateSymmetricEncryptionKey()
    private val carolKey = generateSymmetricEncryptionKey()
    private val daveKey = generateSymmetricEncryptionKey()

    /** Each group's fields as the server holds them, sealed under the key of its first epoch. */
    private val serverVersions = mutableMapOf<String, SealedGroupVersion>()

    /** Alice's and Bob's MLS group for each group. */
    private val aliceGroupIds = mutableMapOf<String, ByteArray>()
    private val bobGroupIds = mutableMapOf<String, ByteArray>()

    /** Who the server holds as each group's current members, and what waits for Carol in each. */
    private val serverMembers = mutableMapOf<String, MutableList<String>>()
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private var messageCount = 0

    /** The groups whose messages the server fails to list. */
    private val unlistable = mutableSetOf<String>()

    /** The groups Carol's device holds, and their members. */
    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()

    init {
        carol.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    @Test
    fun `an older post read after a newer one in another group keeps the newer key`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val groupSync = groupSyncOn(database.memberProfileDao)

            aliceCreates(FIRST_GROUP)
            aliceCreates(SECOND_GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            bobPosts(FIRST_GROUP, key = bobKey)
            bobPosts(SECOND_GROUP, key = bobKey)
            aliceRemovesBob(FIRST_GROUP)
            val newPost = bobPosts(SECOND_GROUP, key = bobNewKey)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val bobs = assertNotNull(database.memberProfileDao.get(userUuid = BOB))

            assertContentEquals(bobNewKey, bobs.profileEncryptionKey)
            assertTrue(bobs.isFromOwner)
            assertEquals(sentAtOf(newPost), bobs.ownerKeySentAt)
            assertTrue(waiting.values.all { it.isEmpty() })
        }
    }

    @Test
    fun `an older post read in a later pass than a newer one keeps the newer key`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val groupSync = groupSyncOn(database.memberProfileDao)

            aliceCreates(FIRST_GROUP)
            aliceCreates(SECOND_GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            bobPosts(FIRST_GROUP, key = bobKey)
            aliceRemovesBob(FIRST_GROUP)
            bobPosts(SECOND_GROUP, key = bobNewKey)

            unlistable += FIRST_GROUP

            assertEquals(
                GroupSyncError.Network(NetworkError.NO_INTERNET),
                groupSync.sync(contactsTakenIn = true).failure,
            )
            assertContentEquals(bobNewKey, database.memberProfileDao.getKey(userUuid = BOB))

            unlistable -= FIRST_GROUP

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(waiting.getValue(FIRST_GROUP).isEmpty())
            assertContentEquals(bobNewKey, database.memberProfileDao.getKey(userUuid = BOB))
        }
    }

    /**
     * Alice's own key goes with each of her Welcomes, as of when the server stored it, and hers
     * rotated between the two: Carol's device joins the second group first, in the order the
     * server lists them.
     */
    @Test
    fun `the creator's key from an older Welcome read after a newer one keeps the newer key`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val groupSync = groupSyncOn(database.memberProfileDao)
            val aliceOldKey = generateSymmetricEncryptionKey()

            aliceCreates(FIRST_GROUP, aliceOwnKey = aliceOldKey)
            val newerWelcome = aliceCreates(SECOND_GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val alices = assertNotNull(database.memberProfileDao.get(userUuid = ALICE))

            assertContentEquals(aliceKey, alices.profileEncryptionKey)
            assertTrue(alices.isFromOwner)
            assertEquals(sentAtOf(newerWelcome), alices.ownerKeySentAt)
        }
    }

    /**
     * Alice added Dave to the first group while she still held her old key, and to the second
     * once hers rotated, and her map of the keys there is the newer: Carol's device reads the
     * second group first.
     */
    @Test
    fun `the creator's key from an older addition read after a newer one keeps the newer key`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val groupSync = groupSyncOn(database.memberProfileDao)
            val aliceOldKey = generateSymmetricEncryptionKey()

            aliceCreates(FIRST_GROUP, aliceOwnKey = aliceOldKey)
            aliceCreates(SECOND_GROUP, aliceOwnKey = aliceOldKey)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            aliceAddsDave(FIRST_GROUP, aliceOwnKey = aliceOldKey)
            val newerKeys = aliceAddsDave(SECOND_GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val alices = assertNotNull(database.memberProfileDao.get(userUuid = ALICE))

            assertContentEquals(aliceKey, alices.profileEncryptionKey)
            assertTrue(alices.isFromOwner)
            assertEquals(sentAtOf(newerKeys), alices.ownerKeySentAt)
            assertTrue(waiting.values.all { it.isEmpty() })
        }
    }

    /**
     * Alice's addition of Dave to the first group was built while she still held her old key, and
     * went out only once hers rotated, as a resend does, between her post of the new one into the
     * second group and her addition of Dave there: the older key comes in a message stored after
     * the first of the two that carry the newer one, and before the second.
     */
    @Test
    fun `the creator's older key sent between two messages of the newer one keeps the newer key`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                val groupSync = groupSyncOn(database.memberProfileDao)
                val aliceOldKey = generateSymmetricEncryptionKey()

                aliceCreates(FIRST_GROUP, aliceOwnKey = aliceOldKey)
                aliceCreates(SECOND_GROUP, aliceOwnKey = aliceOldKey)

                assertNull(groupSync.sync(contactsTakenIn = true).failure)

                alicePosts(SECOND_GROUP, key = aliceKey)
                aliceAddsDave(FIRST_GROUP, aliceOwnKey = aliceOldKey)
                val latestKeys = aliceAddsDave(SECOND_GROUP)

                assertNull(groupSync.sync(contactsTakenIn = true).failure)

                val alices = assertNotNull(database.memberProfileDao.get(userUuid = ALICE))

                assertContentEquals(aliceKey, alices.profileEncryptionKey)
                assertEquals(sentAtOf(latestKeys), alices.ownerKeySentAt)
                assertTrue(waiting.values.all { it.isEmpty() })
            }
        }

    /** Carol's group sync, the keys it takes written through [memberProfileDao]. */
    private fun groupSyncOn(memberProfileDao: MemberProfileDao): GroupSync {
        val memberProfiles = MemberProfileRepository(
            memberProfileDao = memberProfileDao,
            memberProfileVault = InMemoryMemberProfileVault(),
            readFullName = { _, _ -> Result.Error(ProfileError.NotStored) },
            isToRestore = { false },
            markRestored = {},
        )

        return GroupSync(
            userUuid = { CAROL },
            resendPending = { Result.Success(Unit) },
            resendMembershipChanges = { Result.Success(Unit) },
            // What becomes of the copies of the group keys is GroupKeyCopiesTest's.
            openKeyCopies = { Result.Success(emptyMap()) },
            putUpKeyCopies = { Result.Success(Unit) },
            restoreContacts = { Result.Success(Unit) },
            listGroups = { Result.Success(DATABASE_ORDER.mapNotNull(::listedGroup)) },
            storedGroups = { DATABASE_ORDER.mapNotNull { groups[it] } },
            declinedGroups = { emptyList() },
            listMessages = { groupUuid ->
                if (groupUuid in unlistable) {
                    Result.Error(NetworkError.NO_INTERNET)
                } else {
                    Result.Success(ListGroupMessagesResponse(messages = waiting[groupUuid].orEmpty().toList()))
                }
            },
            acknowledge = { groupUuid, messageUuids ->
                waiting.getValue(groupUuid).removeAll { it.uuid in messageUuids }
                Result.Success(Unit)
            },
            leave = { error("Nothing is declined here.") },
            isUnblockedContact = { it == ALICE },
            client = { Result.Success(carol) },
            save = { group, groupMembers, _, _ ->
                groups[group.uuid] = group
                members[group.uuid] = groupMembers
            },
            memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
            replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
            recordSignatureKeys = {},
            // What becomes of the keys each epoch gives is GroupSyncMembershipTest's.
            takeKeys = { _, _ -> },
            updateInformation = { _, _ -> error("Alice edits nothing here.") },
            markLeft = { error("Carol stays in every group.") },
            recordDeclined = { error("Nothing is declined here.") },
            forgetDeclined = { error("Nothing is declined here.") },
            removeDeparted = { _, _ -> error("Nobody leaves a group here.") },
            replaceRefused = { _, _ -> error("No Commit is refused for good here.") },
            remakeOutdated = { Result.Success(null) },
            settleUnlisted = { _, _ -> Result.Success(Unit) },
            keyOf = { _, _, _ -> error("No key is got back through the chain here.") },
            checkKeyLinks = { Result.Success(Unit) },
            setAwaitingOwner = { _, _ -> },
            keyLinks = { Result.Success(emptyList()) },
            now = { error("Nobody departed from a group here.") },
            fullName = { null },
            fullNameEvenIfHidden = { null },
            hasPendingChange = { false },
            hasPendingCreation = { false },
            markDeleted = { error("Nobody deletes a group here.") },
            isBlocked = { false },
            setHandedProfileKey = { _, _ -> },
            takeOwnerKey = memberProfiles::takeOwnerKey,
            fillGap = memberProfiles::fillGap,
            restoreMemberProfiles = { Result.Success(Unit) },
            readMemberNames = { Result.Success(Unit) },
            backUpMemberProfiles = { Result.Success(Unit) },
            handOutProfileKey = { Result.Success(Unit) },
            rebuildWakeList = {},
            resendScheduler = ResendsBooked(),
        )
    }

    /**
     * Has Alice create [groupUuid] with Bob and Carol, her Welcome handing over [aliceOwnKey] as
     * hers and the keys of the other two, and Bob join it, and returns the Welcome as it waits for
     * Carol.
     */
    private suspend fun aliceCreates(groupUuid: String, aliceOwnKey: ByteArray = aliceKey): GroupMessageDto {
        val creation = alice.createGroup(
            groupUuid = groupUuid,
            invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = mapOf(ALICE to aliceOwnKey, BOB to bobKey, CAROL to carolKey)),
        )

        aliceGroupIds[groupUuid] = creation.mlsGroupId
        serverMembers[groupUuid] = mutableListOf(ALICE, BOB, CAROL)
        serverVersions[groupUuid] = FIELDS.sealedAs(
            creator = alice,
            groupKey = alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = groupUuid),
            groupUuid = groupUuid,
        )
        bobGroupIds[groupUuid] = bob.joinGroup(
            welcome = creation.welcome,
            expectedGroupUuid = groupUuid,
            expectedMembers = listOf(ALICE, CAROL),
            pinMembers = false,
        )

        return post(groupUuid, sender = ALICE, ciphertext = creation.welcome)
    }

    /** Has Bob post [key] into [groupUuid] as his own, as [GroupProfileKeyDelivery] does, and returns the post. */
    private suspend fun bobPosts(groupUuid: String, key: ByteArray): GroupMessageDto =
        post(
            groupUuid,
            sender = BOB,
            ciphertext = bob.encryptApplicationMessage(
                mlsGroupId = bobGroupIds.getValue(groupUuid),
                payload = ProfileKeyUpdate(profileEncryptionKey = key).serialize(),
            ),
        )

    /** Has Alice post [key] into [groupUuid] as her own, as [GroupProfileKeyDelivery] does. */
    private suspend fun alicePosts(groupUuid: String, key: ByteArray) {
        post(
            groupUuid,
            sender = ALICE,
            ciphertext = alice.encryptApplicationMessage(
                mlsGroupId = aliceGroupIds.getValue(groupUuid),
                payload = ProfileKeyUpdate(profileEncryptionKey = key).serialize(),
            ),
        )
    }

    /**
     * Has Alice add Dave to [groupUuid] as [GroupMembershipChanger] does, handing over [aliceOwnKey]
     * as hers and the keys of the other three in a map encrypted ahead of the Commit, which the
     * server lists to Carol before the Commit, as the API does, and returns the map as it waits.
     */
    private suspend fun aliceAddsDave(groupUuid: String, aliceOwnKey: ByteArray = aliceKey): GroupMessageDto {
        val aliceGroupId = aliceGroupIds.getValue(groupUuid)
        val profileKeys = mapOf(ALICE to aliceOwnKey, BOB to bobKey, CAROL to carolKey, DAVE to daveKey)
        val keysMessage = alice.encryptApplicationMessage(
            mlsGroupId = aliceGroupId,
            payload = profileKeysMessageOf(profileKeys = profileKeys),
        )
        val addition = alice.addMembersTo(
            mlsGroupId = aliceGroupId,
            invitees = listOf(dave.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = profileKeys))

        alice.applyPendingCommitIn(mlsGroupId = aliceGroupId, groupUuid = groupUuid)
        serverMembers.getValue(groupUuid) += DAVE

        val keys = post(groupUuid, sender = ALICE, ciphertext = keysMessage)

        post(groupUuid, sender = ALICE, ciphertext = addition.commit)

        return keys
    }

    /** Has Alice remove Bob from [groupUuid], and the server deliver the Commit to Carol. */
    private suspend fun aliceRemovesBob(groupUuid: String) {
        val aliceGroupId = aliceGroupIds.getValue(groupUuid)
        val commit = alice.removeMembersFrom(mlsGroupId = aliceGroupId, userUuids = listOf(BOB))

        alice.applyPendingCommitIn(mlsGroupId = aliceGroupId, groupUuid = groupUuid)
        serverMembers.getValue(groupUuid) -= BOB

        post(groupUuid, sender = ALICE, ciphertext = commit)
    }

    /**
     * Has the server put [ciphertext], Base64-encoded, in Carol's queue in [groupUuid], as sent by
     * [sender], stamped as the API stamps it, a second after the message before, and returns it.
     */
    private fun post(groupUuid: String, sender: String, ciphertext: ByteArray): GroupMessageDto {
        val seconds = ++messageCount
        val message = GroupMessageDto(
            uuid = "m$seconds",
            senderUserUuid = sender,
            ciphertext = Base64.Default.encode(ciphertext),
            createdAt = "2026-10-28T09:${(seconds / 60).twoDigits()}:${(seconds % 60).twoDigits()}.000Z",
            departedMemberUuids = emptyList(),
        )

        waiting.getOrPut(groupUuid) { mutableListOf() } += message

        return message
    }

    /** [groupUuid] as the server lists it to Carol, once created. */
    private fun listedGroup(groupUuid: String): GroupDto? {
        val version = serverVersions[groupUuid] ?: return null

        return listedGroupOf(
            uuid = groupUuid,
            ownerUuid = ALICE,
            version = version,
            memberUuids = serverMembers.getValue(groupUuid).toList(),
            keyCopy = null,
        )
    }

    /** When the server stored [message], in milliseconds since the epoch. */
    private fun sentAtOf(message: GroupMessageDto): Long = Instant.parse(message.createdAt).toEpochMilliseconds()
}

/**
 * The order the groups come in, from the database as from the server: the second one first, which
 * nothing rules out, and which is the order that let the older post win.
 */
private val DATABASE_ORDER = listOf(SECOND_GROUP, FIRST_GROUP)

private fun Int.twoDigits() = toString().padStart(2, '0')
