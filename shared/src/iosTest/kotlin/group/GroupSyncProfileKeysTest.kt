package group

import contact.ProfileKeyUpdate
import database.data.local.dao.ContactTables
import database.data.local.dao.MemberProfileTables
import database.data.local.entity.ContactEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.addMembersTo
import mls.applyPendingCommitIn
import mls.encryptApplicationMessage
import mls.inviteeKeyPackage
import mls.processGroupMessageIn
import mls.removeMembersFrom
import profile.InMemoryProfileStore
import profile.ProfileRepository
import profile.UsersWritten
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.Result
import vault.InMemoryMemberProfileVault
import vault.InMemoryProfileKeyVault
import vault.MemberProfileBook
import vault.VaultMemberProfile
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val ERIN = "55555555-5555-5555-5555-555555555555"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What Bob's group sync makes of the keys to the members' profiles handed over in the group Alice
 * created with him and Carol in it: those Alice relays, in her Welcome and with each addition, and
 * those the members post of their own, his included, with every MLS client made through the
 * library itself, as [GroupCreator] and [GroupMembershipChanger] build what Alice sends, and the
 * server delivering it as the API does. The keys and names land in [MemberProfileTables], the vault
 * is stood in for by [InMemoryMemberProfileVault] and the profiles by [InMemoryProfileStore].
 *
 * Alice and Dave are Bob's contacts, both pinned. Carol is nobody to Bob, nor is Erin, whom Alice
 * adds, and Mallory is a stranger to everybody.
 */
class GroupSyncProfileKeysTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())
    private val erin = MlsClient(userUuid = ERIN, secret = ByteArray(32) { 5 }, store = InMemoryMlsStore())

    /** The key to each one's profile. */
    private val aliceKey = generateSymmetricEncryptionKey()
    private val bobKey = generateSymmetricEncryptionKey()
    private val carolKey = generateSymmetricEncryptionKey()
    private val daveKey = generateSymmetricEncryptionKey()
    private val erinKey = generateSymmetricEncryptionKey()

    /** The keys Alice's device holds of each member's profile, which she hands over, hers included. */
    private val aliceHolds = mutableMapOf(
        ALICE to aliceKey,
        BOB to bobKey,
        CAROL to carolKey,
        DAVE to daveKey,
        ERIN to erinKey,
    )

    /** The key to Bob's own profile, as his vault and device hold it. */
    private val bobProfileKeys = InMemoryProfileKeyVault(profileEncryptionKey = bobKey)

    /** Bob's contacts, and who his vault blocks. */
    private val contactTables = ContactTables().apply {
        add(contactOf(ALICE, key = aliceKey))
        add(contactOf(DAVE, key = daveKey))
    }
    private val blocked = mutableSetOf<String>()

    /** What Bob's device learned of the members' profiles, what his vault holds of it, and the profiles. */
    private val tables = MemberProfileTables(contacts = contactTables)
    private val memberVault = InMemoryMemberProfileVault()
    private val profileStore = InMemoryProfileStore()
    private var toRestore = false

    private val memberProfiles = MemberProfileRepository(
        memberProfileDao = tables.memberProfileDao,
        memberProfileVault = memberVault,
        readFullName = ProfileRepository(
            profileStore = profileStore,
            profileKeyVault = bobProfileKeys,
            userDao = UsersWritten(),
        )::getFullName,
        isToRestore = { toRestore },
        markRestored = { toRestore = false },
    )

    /** Alice's MLS group, the key she last handed over, and the fields as the server holds them. */
    private var aliceGroupId: ByteArray? = null
    private var groupKey = generateSymmetricEncryptionKey()
    private var serverFields: SealedGroupFields? = null

    /** Who the server holds as the group's current members. */
    private val serverMembers = mutableListOf<String>()

    /** What waits for Bob in the group, and what he acknowledged. */
    private val waiting = mutableListOf<GroupMessageDto>()
    private val acknowledged = mutableListOf<String>()
    private var messageCount = 0

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()

    /** The Welcome Alice's creation let Carol in with. */
    private var carolsWelcome: ByteArray? = null

    /** The Welcome Alice's last addition let its newcomers in with. */
    private var additionsWelcome: ByteArray? = null

    /**
     * What Bob posted into his groups, in order, but for what [joined] posted, which is in
     * [joinPosts], and the resends booked.
     */
    private val posts = mutableListOf<Pair<String, ByteArray>>()
    private var joinPosts = listOf<Pair<String, ByteArray>>()
    private val resends = ResendsBooked()

    /** The groups a change of Bob's waits on the server in, as on a creator's device. */
    private val pending = mutableSetOf<String>()

    private val delivery = GroupProfileKeyDelivery(
        profileKeyVault = bobProfileKeys,
        // As GroupDao.getOwedProfileKey selects them, but for a change pending, which the sync
        // keeps out on its own, see GroupDaoTest for the query itself.
        owedGroups = { hash ->
            groups.values.filter {
                !it.isLeft && it.mlsGroupId != null && it.ownerUuid !in blocked && it.handedProfileKeyHash != hash
            }
        },
        setHanded = { groupUuid, hash ->
            groups[groupUuid] = groups.getValue(groupUuid).with(handedProfileKeyHash = hash)
        },
        client = { Result.Success(bob) },
        post = { groupUuid, message ->
            posts += groupUuid to message
            GroupPostAnswer.Posted
        },
        resendScheduler = resends,
    )

    private val groupSync = GroupSync(
        userUuid = { BOB },
        resendPending = { Result.Success(Unit) },
        resendMembershipChanges = { Result.Success(Unit) },
        // What becomes of the copies of the group keys is GroupKeyCopiesTest's.
        openKeyCopies = { Result.Success(emptyMap()) },
        putUpKeyCopies = { Result.Success(Unit) },
        restoreContacts = { Result.Success(Unit) },
        listGroups = { Result.Success(listOfNotNull(listedGroup())) },
        storedGroups = { groups.values.toList() },
        declinedGroups = { emptyList() },
        listMessages = { Result.Success(ListGroupMessagesResponse(messages = waiting.toList())) },
        acknowledge = { _, messageUuids ->
            acknowledged += messageUuids
            waiting.removeAll { it.uuid in messageUuids }
            Result.Success(Unit)
        },
        leave = { error("Nothing is declined here.") },
        isUnblockedContact = { it in contactTables.contacts && it !in blocked },
        client = { Result.Success(bob) },
        save = { group, groupMembers ->
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
        },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        updateKey = { groupUuid, key -> groups[groupUuid] = groups.getValue(groupUuid).with(groupKey = key) },
        markLeft = { groupUuid -> groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true) },
        recordDeclined = { error("Nothing is declined here.") },
        forgetDeclined = { error("Nothing is declined here.") },
        removeDeparted = { _, _ -> error("Nobody leaves a group here.") },
        fullName = { null },
        hasPendingChange = { it in pending },
        isBlocked = { it in blocked },
        setHandedProfileKey = { groupUuid, hash ->
            groups[groupUuid] = groups.getValue(groupUuid).with(handedProfileKeyHash = hash)
        },
        takeOwnerKey = memberProfiles::takeOwnerKey,
        fillGap = memberProfiles::fillGap,
        restoreMemberProfiles = memberProfiles::restore,
        readMemberNames = memberProfiles::readNames,
        backUpMemberProfiles = memberProfiles::backUp,
        handOutProfileKey = delivery::handOut,
        resendScheduler = resends,
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
    }

    @Test
    fun `the creator's Welcome hands over her own key and relays everybody else's`() = runBlocking<Unit> {
        joined()

        val alices = row(ALICE)

        assertContentEquals(aliceKey, alices.profileEncryptionKey)
        assertTrue(alices.isFromOwner)

        val carols = row(CAROL)

        assertContentEquals(carolKey, carols.profileEncryptionKey)
        assertFalse(carols.isFromOwner)

        assertFalse(BOB in tables.rows)
    }

    /**
     * Carol can hold an older key of Bob's, learned in a group the two of them have since left,
     * which the one Alice relays fills no gap for: only Bob's own post replaces it.
     */
    @Test
    fun `a Welcome holding the user's current key has the sync post it all the same`() = runBlocking<Unit> {
        joined()

        assertEquals(listOf(GROUP), joinPosts.map { it.first })
        assertContentEquals(bobKey, readByCarol(joinPosts.single().second))
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(GROUP).handedProfileKeyHash)
    }

    /** Alice was handed an older key of Bob's, his newer one having reached his contacts only. */
    @Test
    fun `a Welcome holding an older key for the user has the sync post the current one`() = runBlocking<Unit> {
        aliceHolds[BOB] = generateSymmetricEncryptionKey()

        joined()

        assertEquals(listOf(GROUP), joinPosts.map { it.first })
        assertContentEquals(bobKey, readByCarol(joinPosts.single().second))
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(GROUP).handedProfileKeyHash)
    }

    @Test
    fun `a Welcome leaving the user out has the sync post their key`() = runBlocking<Unit> {
        aliceHolds.remove(BOB)

        joined()

        assertEquals(listOf(GROUP), joinPosts.map { it.first })
        assertContentEquals(bobKey, readByCarol(joinPosts.single().second))
    }

    /** As the API lists them. */
    @Test
    fun `the keys handed over with an addition are taken when listed before the Commit`() = runBlocking<Unit> {
        joined()

        val (keys, commit) = aliceAdds(erin, keysFirst = true)
        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(keys, commit), acknowledged.toList())
        assertRelayed(ERIN, erinKey)
        assertEquals(setOf(ALICE, BOB, CAROL, ERIN), members.getValue(GROUP).mapTo(HashSet()) { it.userUuid })
    }

    /** Read in the epoch it was sent in, which the library keeps once past it. */
    @Test
    fun `the keys handed over with an addition are taken when listed after the Commit`() = runBlocking<Unit> {
        joined()

        val (keys, commit) = aliceAdds(erin, keysFirst = false)
        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(commit, keys), acknowledged.toList())
        assertRelayed(ERIN, erinKey)
    }

    /** Whom Alice seats next joins with that key for Bob, which his own post has to replace. */
    @Test
    fun `an addition holding an older key for the user has the sync post the current one`() = runBlocking<Unit> {
        joined()

        aliceHolds[BOB] = generateSymmetricEncryptionKey()
        aliceAdds(erin)

        groupSync.sync(contactsTakenIn = true)

        assertEquals(listOf(GROUP), posts.map { it.first })
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(GROUP).handedProfileKeyHash)
    }

    /**
     * Erin can hold an older key of Bob's, learned in a group the two of them shared before, which
     * the one Alice relays fills no gap for. Posted in the epoch the addition seats her in, once
     * the sync read it.
     */
    @Test
    fun `an addition has the sync post the user's key again for whom it seats`() = runBlocking<Unit> {
        joined()

        aliceAdds(erin)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(listOf(GROUP), posts.map { it.first })
        assertContentEquals(bobKey, readByErin(posts.single().second))
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(GROUP).handedProfileKeyHash)
    }

    /**
     * The Commit seating Erin waits behind a conflict over Dave's identity, so the entry Alice
     * relayed for Erin names nobody in the group yet, and is dropped with the map, spent. Erin's
     * own post, which she makes as she joins, comes after that Commit, and names her once it is read.
     */
    @Test
    fun `a newcomer whose relayed key a waiting Commit kept out is named by their own post`() =
        runBlocking<Unit> {
            joined()

            val impostor = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 9 }, store = InMemoryMlsStore())
            val (keys, commit) = aliceAdds(erin, impostor)

            assertEquals(GroupSyncError.IdentityChanged, groupSync.sync(contactsTakenIn = true).failure)
            assertEquals(listOf(keys), acknowledged.toList())
            assertFalse(ERIN in tables.rows)

            // Settled as the user would, once Dave told them his key changed.
            bob.clearIdentityPin(userUuid = DAVE)

            val erinsPost = post(
                sender = ERIN,
                ciphertext = erin.encryptApplicationMessage(
                    mlsGroupId = joinAddition(erin, otherMembers = listOf(ALICE, BOB, CAROL, DAVE)),
                    payload = ProfileKeyUpdate(profileEncryptionKey = erinKey).serialize(),
                ),
            )

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(listOf(keys, commit, erinsPost), acknowledged.toList())
            assertContentEquals(erinKey, row(ERIN).profileEncryptionKey)
            assertTrue(row(ERIN).isFromOwner)
        }

    /** Any member can send an application message: only the creator's relays anybody's key. */
    @Test
    fun `the keys a member other than the creator hands over are not taken`() = runBlocking<Unit> {
        joined()

        val handedOver = post(
            sender = CAROL,
            ciphertext = carol.encryptApplicationMessage(
                mlsGroupId = carolJoins(),
                payload = profileKeysMessageOf(
                    profileKeys = mapOf(ALICE to generateSymmetricEncryptionKey(), BOB to bobKey),
                ),
            ),
        )

        groupSync.sync(contactsTakenIn = true)

        assertEquals(listOf(handedOver), acknowledged.toList())
        assertContentEquals(aliceKey, row(ALICE).profileEncryptionKey)
        assertEquals(setOf(ALICE, CAROL), tables.rows.keys)
    }

    @Test
    fun `a member's own key replaces the one the creator relayed for them`() = runBlocking<Unit> {
        joined()

        val carolNewKey = generateSymmetricEncryptionKey()

        carolPosts(carolNewKey)
        groupSync.sync(contactsTakenIn = true)

        val carols = row(CAROL)

        assertContentEquals(carolNewKey, carols.profileEncryptionKey)
        assertTrue(carols.isFromOwner)
    }

    /** Alice still holds the key Carol replaced since. */
    @Test
    fun `a key the creator relays never replaces one its owner posted`() = runBlocking<Unit> {
        joined()

        val carolNewKey = generateSymmetricEncryptionKey()

        carolPosts(carolNewKey)
        groupSync.sync(contactsTakenIn = true)

        aliceAdds(erin)
        groupSync.sync(contactsTakenIn = true)

        assertContentEquals(carolNewKey, row(CAROL).profileEncryptionKey)
        assertTrue(row(CAROL).isFromOwner)
    }

    /** Whatever Alice wrote: only a 32-byte key opens a profile, and only a member's is of use. */
    @Test
    fun `keys that are no profile key and members not in the group are not taken`() = runBlocking<Unit> {
        aliceHolds[CAROL] = ByteArray(33)

        joined(extraKeys = mapOf(MALLORY to generateSymmetricEncryptionKey()))

        assertEquals(setOf(ALICE), tables.rows.keys)

        val shortKey = post(
            sender = CAROL,
            ciphertext = carol.encryptApplicationMessage(
                mlsGroupId = carolJoins(),
                payload = ProfileKeyUpdate(profileEncryptionKey = ByteArray(16)).serialize(),
            ),
        )

        groupSync.sync(contactsTakenIn = true)

        assertEquals(listOf(shortKey), acknowledged.toList())
        assertEquals(setOf(ALICE), tables.rows.keys)
    }

    /**
     * Who is in the group is its creator's word, and an identity conflict on her leaves it vouching
     * for nobody: nothing is taken from it, and nothing posted into it, see GroupDao.getOwedProfileKey.
     */
    @Test
    fun `nothing is taken from a group whose creator is blocked and nothing is posted into it`() =
        runBlocking<Unit> {
            joined()

            blocked += ALICE

            val carolsPost = carolPosts(generateSymmetricEncryptionKey())

            bobProfileKeys.replaceProfileEncryptionKey(profileEncryptionKey = generateSymmetricEncryptionKey())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(carolsPost), acknowledged.toList())
            assertContentEquals(carolKey, row(CAROL).profileEncryptionKey)
            assertFalse(row(CAROL).isFromOwner)
            assertTrue(posts.isEmpty())
        }

    @Test
    fun `nothing is taken from a group the user is removed from`() = runBlocking<Unit> {
        joined()

        val carolsPost = carolPosts(generateSymmetricEncryptionKey())
        val removal = aliceRemoves(BOB)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertEquals(listOf(GROUP), pass.removed.map { it.groupUuid })
        assertEquals(listOf(carolsPost, removal), acknowledged.toList())
        assertContentEquals(carolKey, row(CAROL).profileEncryptionKey)
        assertFalse(row(CAROL).isFromOwner)
    }

    /** Taken in before, Alice's relayed key would keep out the one Carol handed over, which the vault kept. */
    @Test
    fun `the members the vault kept are brought back before anything is taken from a group`() = runBlocking<Unit> {
        val carolNewKey = generateSymmetricEncryptionKey()

        toRestore = true
        memberVault.book = MemberProfileBook.EMPTY.with(
            userUuid = CAROL,
            member = VaultMemberProfile(profileEncryptionKey = carolNewKey, isFromOwner = true, fullName = "Carol"),
        )

        joined()

        assertEquals(1, memberVault.reads)
        assertFalse(toRestore)
        assertContentEquals(carolNewKey, row(CAROL).profileEncryptionKey)
        assertTrue(row(CAROL).isFromOwner)
    }

    /** Alice is a contact, whose name the contact sync reads. */
    @Test
    fun `the names of members who are not contacts are read and backed up once the groups are synced`() =
        runBlocking<Unit> {
            upload(CAROL, key = carolKey, fullName = "Carol Blanc")
            upload(ALICE, key = aliceKey, fullName = "Alice Martin")

            joined()

            assertEquals("Carol Blanc", row(CAROL).fullName)
            assertNull(row(ALICE).fullName)
            assertEquals("Carol Blanc", memberVault.book.members[CAROL]?.fullName)
            assertTrue(tables.rows.values.all { it.isBackedUp })
        }

    /** Removing a contact rotates the key, see `ProfileRepository.rotateProfileKey`. */
    @Test
    fun `a rotation of the user's key has the next sync post the new one into the group`() = runBlocking<Unit> {
        joined()

        val newKey = generateSymmetricEncryptionKey()

        bobProfileKeys.replaceProfileEncryptionKey(profileEncryptionKey = newKey)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(listOf(GROUP), posts.map { it.first })
        assertContentEquals(newKey, readByCarol(posts.single().second))
        assertEquals(profileEncryptionKeyPartialHash(newKey), groups.getValue(GROUP).handedProfileKeyHash)
    }

    /**
     * As on the creator's device, whose addition waits on the server: Bob stands in for him. The
     * post would go out in the epoch the change takes the group from, which those it seats cannot
     * read.
     */
    @Test
    fun `a group with a change of the user's pending is not posted into`() = runBlocking<Unit> {
        joined()

        val newKey = generateSymmetricEncryptionKey()

        bobProfileKeys.replaceProfileEncryptionKey(profileEncryptionKey = newKey)
        pending += GROUP

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(posts.isEmpty())
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(GROUP).handedProfileKeyHash)

        pending -= GROUP

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertContentEquals(newKey, readByCarol(posts.single().second))
    }

    /**
     * Somebody holding Dave's UUID under a key of their own is who the server served Alice as him:
     * the group waits behind that Commit, an epoch short of its members, who would seat Dave's
     * impostor with Bob's post unreadable to them.
     */
    @Test
    fun `a group the sync could not catch up with is not posted into`() = runBlocking<Unit> {
        joined()

        bobProfileKeys.replaceProfileEncryptionKey(profileEncryptionKey = generateSymmetricEncryptionKey())

        aliceAdds(MlsClient(userUuid = DAVE, secret = ByteArray(32) { 9 }, store = InMemoryMlsStore()))

        val pass = groupSync.sync(contactsTakenIn = true)

        assertEquals(GroupSyncError.IdentityChanged, pass.failure)
        assertTrue(posts.isEmpty())
    }

    /** The row of [userUuid] Bob's device holds. */
    private fun row(userUuid: String) = tables.rows.getValue(userUuid)

    /** That Bob's device holds [key] for [userUuid], as relayed. */
    private fun assertRelayed(userUuid: String, key: ByteArray) {
        assertContentEquals(key, row(userUuid).profileEncryptionKey)
        assertFalse(row(userUuid).isFromOwner)
    }

    /**
     * Has Alice create [GROUP] with Bob and Carol, her Welcome handing over the keys she holds of
     * the three of them and [extraKeys], and Bob's sync join it, and forgets what it acknowledged.
     */
    private suspend fun joined(extraKeys: Map<String, ByteArray> = emptyMap()) {
        val creation = alice.createGroup(
            groupUuid = GROUP,
            invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(
                groupKey = groupKey,
                profileKeys = aliceHoldsOf(listOf(ALICE, BOB, CAROL)) + extraKeys,
            ),
        )

        aliceGroupId = creation.mlsGroupId
        carolsWelcome = creation.welcome
        serverMembers += listOf(ALICE, BOB, CAROL)
        serverFields = checkNotNull(FIELDS.seal(groupKey = groupKey, groupUuid = GROUP))

        post(sender = ALICE, ciphertext = creation.welcome)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })

        acknowledged.clear()
        joinPosts = posts.toList()
        posts.clear()
    }

    /**
     * Has Alice add [invitees] as [GroupMembershipChanger] does: the keys she holds of every member
     * once they are in, encrypted ahead of the Commit, which the server delivers to Bob before the
     * Commit when [keysFirst], after it otherwise. Returns the UUIDs of the two, keys first.
     */
    private suspend fun aliceAdds(vararg invitees: MlsClient, keysFirst: Boolean = true): Pair<String, String> {
        val memberUuids = serverMembers + invitees.map { it.userUuid() }
        val profileKeys = aliceHoldsOf(memberUuids)
        val keysMessage = alice.encryptApplicationMessage(
            mlsGroupId = aliceGroup(),
            payload = profileKeysMessageOf(profileKeys = profileKeys),
        )
        val addition = alice.addMembersTo(
            mlsGroupId = aliceGroup(),
            invitees = invitees.map { it.inviteeKeyPackage() },
            welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = profileKeys),
        )

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())
        serverMembers += invitees.map { it.userUuid() }
        additionsWelcome = addition.welcome

        return if (keysFirst) {
            val keys = post(sender = ALICE, ciphertext = keysMessage)

            keys to post(sender = ALICE, ciphertext = addition.commit)
        } else {
            val commit = post(sender = ALICE, ciphertext = addition.commit)

            post(sender = ALICE, ciphertext = keysMessage) to commit
        }
    }

    /** Has Alice remove [userUuid], and the server deliver the Commit to Bob, and returns its UUID. */
    private suspend fun aliceRemoves(userUuid: String): String {
        val commit = alice.removeMembersFrom(mlsGroupId = aliceGroup(), userUuids = listOf(userUuid))

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())

        val delivered = post(sender = ALICE, ciphertext = commit)

        serverMembers -= userUuid

        return delivered
    }

    /** Has Carol post [key] as her own, as [GroupProfileKeyDelivery] does on her device, and returns its UUID. */
    private suspend fun carolPosts(key: ByteArray): String =
        post(
            sender = CAROL,
            ciphertext = carol.encryptApplicationMessage(
                mlsGroupId = carolJoins(),
                payload = ProfileKeyUpdate(profileEncryptionKey = key).serialize(),
            ),
        )

    /** What Carol reads in [post], one of Bob's, as the key to his profile. */
    private suspend fun readByCarol(post: ByteArray): ByteArray? {
        val read = assertIs<ProcessedMessage.ApplicationMessage>(
            carol.processGroupMessageIn(mlsGroupId = carolJoins(), message = post, creatorUuid = ALICE).processed,
        )

        assertEquals(BOB, read.senderUserUuid)

        return ownProfileKeyOf(read.payload)
    }

    /** What Erin reads in [post], one of Bob's, as the key to his profile, once she joined. */
    private suspend fun readByErin(post: ByteArray): ByteArray? {
        val read = assertIs<ProcessedMessage.ApplicationMessage>(
            erin.processGroupMessageIn(
                mlsGroupId = joinAddition(erin, otherMembers = listOf(ALICE, BOB, CAROL)),
                message = post,
                creatorUuid = ALICE,
            ).processed,
        )

        assertEquals(BOB, read.senderUserUuid)

        return ownProfileKeyOf(read.payload)
    }

    /** Has [newcomer] join Alice's group from her last addition's Welcome, seating [otherMembers] with them. */
    private fun joinAddition(newcomer: MlsClient, otherMembers: List<String>): ByteArray =
        newcomer.joinGroup(
            welcome = assertNotNull(additionsWelcome),
            expectedGroupUuid = GROUP,
            expectedMembers = otherMembers,
            pinMembers = false,
        )

    /** The keys Alice holds of [memberUuids]. */
    private fun aliceHoldsOf(memberUuids: List<String>): Map<String, ByteArray> =
        memberUuids.mapNotNull { userUuid -> aliceHolds[userUuid]?.let { userUuid to it } }.toMap()

    /** [userUuid]'s profile, filled in under [key]. */
    private suspend fun upload(userUuid: String, key: ByteArray, fullName: String) {
        val theirs = ProfileRepository(
            profileStore = profileStore,
            profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = key),
            userDao = UsersWritten(),
        )

        assertIs<Result.Success<Unit>>(theirs.uploadProfile(userUuid = userUuid, username = "", fullName = fullName))
    }

    /**
     * Has the server put [ciphertext], Base64-encoded, in Bob's queue, as sent by [sender], stamped
     * as the API stamps it, a second after the message before, and returns its UUID.
     */
    private fun post(sender: String, ciphertext: ByteArray): String {
        val seconds = ++messageCount
        val uuid = "m$seconds"

        waiting += GroupMessageDto(
            uuid = uuid,
            senderUserUuid = sender,
            ciphertext = Base64.Default.encode(ciphertext),
            createdAt = "2026-10-27T12:${(seconds / 60).twoDigits()}:${(seconds % 60).twoDigits()}.000Z",
        )

        return uuid
    }

    /** The group as the server lists it to Bob: to its current members alone. */
    private fun listedGroup(): GroupDto? {
        val fields = serverFields ?: return null

        if (BOB !in serverMembers) return null

        return GroupDto(
            uuid = GROUP,
            ownerUuid = ALICE,
            title = fields.title,
            description = fields.description,
            currency = fields.currency,
            members = serverMembers.map { GroupMemberDto(userUuid = it) },
            keyCopy = null,
        )
    }

    /** Carol's copy of Alice's group, joined from its Welcome the first time it is asked for. */
    private var carolGroupId: ByteArray? = null

    private fun carolJoins(): ByteArray = carolGroupId ?: carol.joinGroup(
        welcome = assertNotNull(carolsWelcome),
        expectedGroupUuid = GROUP,
        expectedMembers = listOf(ALICE, BOB),
        pinMembers = false,
    ).also { carolGroupId = it }

    private fun aliceGroup() = assertNotNull(aliceGroupId)
}

private fun Int.twoDigits() = toString().padStart(2, '0')

/** A contact of Bob's who handed him [key]. */
private fun contactOf(userUuid: String, key: ByteArray) =
    ContactEntity(
        userUuid = userUuid,
        profileEncryptionKey = key,
        handedProfileKeyHash = null,
        verifiedSafetyNumber = null,
        isBackedUp = true,
    )

private fun GroupEntity.with(
    groupKey: ByteArray = this.groupKey,
    isLeft: Boolean = this.isLeft,
    handedProfileKeyHash: String? = this.handedProfileKeyHash,
) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
