package database.data.repository

import authenticationUtils.SessionExpiry
import channel.DirectChannelRepository
import contact.AccountDeletionNotice
import contact.ContactMessageHandler
import contact.ContactRemoval
import contact.ContactSyncError
import contact.ProfileKeyPull
import contact.ProfileKeyRequest
import contact.ProfileKeyUpdate
import contact.RemoveContactError
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.ContactTables
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.LocalDataDao
import database.data.local.dao.NoChannels
import database.data.local.dao.NothingAwaited
import database.data.local.dao.copy
import database.data.local.entity.ContactEntity
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.OpenedContactRequest
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.local.entity.PendingProfileKeyEntity
import database.data.remote.ContactApi
import database.data.remote.DirectMessageApi
import database.data.remote.UserApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import message.DirectMessageHandling
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.KeyPackageApi
import mls.MlsIdentityRepository
import networking.DuckCountClient
import profile.InMemoryProfileStore
import profile.ProfileError
import profile.ProfileRepository
import profile.UsersWritten
import push.WakeList
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import vault.ContactBook
import vault.InMemoryProfileKeyVault
import vault.VaultApi
import vault.VaultContact
import vault.VaultRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val USER = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "77777777-7777-7777-7777-777777777777"
private const val ERIN = "99999999-9999-9999-9999-999999999999"

/**
 * What the messages about the contact relationship, and the reads of the contacts' profiles,
 * make of the user's contacts, with the database stood in for by [ContactTables] and the server
 * by [InMemoryProfileStore]: nothing here reaches the network.
 *
 * Alice is one of the user's contacts. Her profile went up under a first key, which the user
 * holds, and a removal of hers rotated it since, see [aliceRotates].
 */
class ContactRepositoryTest {

    private val tables = ContactTables()
    private val store = InMemoryProfileStore()

    private val userFirstKey = generateSymmetricEncryptionKey()
    private val userVault = InMemoryProfileKeyVault(profileEncryptionKey = userFirstKey)

    private val aliceFirstKey = generateSymmetricEncryptionKey()
    private val aliceVault = InMemoryProfileKeyVault(profileEncryptionKey = aliceFirstKey)
    private val aliceProfiles = ProfileRepository(profileStore = store, profileKeyVault = aliceVault, userDao = UsersWritten())

    /** Who the user replied to, in which group. */
    private val replies = mutableListOf<Pair<String, ByteArray>>()

    private val client = DuckCountClient(
        sessionExpiry = SessionExpiry(),
        localDataDao = NoLocalData,
        sessionLeftovers = emptyList(),
    )

    private val vaultRepository = VaultRepository(vaultApi = VaultApi(client = client))
    private val wakeList = WakeList(contactDao = tables.contactDao, contactRequestDao = NoRequests, store = null)

    private val mlsIdentityRepository = MlsIdentityRepository(
        mlsStore = InMemoryMlsStore(),
        vaultRepository = vaultRepository,
        identityConflictDao = tables.identityConflictDao,
        wakeList = wakeList,
    )

    private val profiles = ProfileRepository(profileStore = store, profileKeyVault = userVault, userDao = tables.userDao)

    private val contacts = contactsWith(directChannelDao = NoChannels)

    /** The user's contacts, with the channels to them in [directChannelDao]. */
    private fun contactsWith(directChannelDao: DirectChannelDao) = ContactRepository(
        contactDao = tables.contactDao,
        directChannelDao = directChannelDao,
        identityConflictDao = tables.identityConflictDao,
        pendingExpenseKeyDao = NothingAwaited,
        pendingProfileKeyDao = tables.pendingProfileKeyDao,
        userRepository = UserRepository(userDao = tables.userDao, userApi = UserApi(client = client)),
        vaultRepository = vaultRepository,
        profileRepository = profiles,
        directMessageApi = DirectMessageApi(client = client),
        mlsIdentityRepository = mlsIdentityRepository,
        wakeList = wakeList,
    )

    private val pull = ProfileKeyPull(
        pendingProfileKeyDao = tables.pendingProfileKeyDao,
        contactDao = tables.contactDao,
        profileKeyVault = userVault,
        send = { _, _ -> error("Not used here") },
        claimReply = { true },
        replyIn = { recipientUserUuid, mlsGroupId, _ ->
            replies += recipientUserUuid to mlsGroupId
            Result.Success(Unit)
        },
        resendScheduler = ResendsBooked(),
    )

    private val handler = ContactMessageHandler(
        contactRequestRepository = ContactRequestRepository(
            contactRequestDao = NoRequests,
            identityConflictDao = tables.identityConflictDao,
            contactApi = ContactApi(client = client),
            vaultRepository = vaultRepository,
            mlsIdentityRepository = mlsIdentityRepository,
            profileRepository = profiles,
            contactRepository = contacts,
            directChannelRepository = DirectChannelRepository(
                directChannelDao = NoChannels,
                contactDao = tables.contactDao,
                identityConflictDao = tables.identityConflictDao,
                keyPackageApi = KeyPackageApi(client = client),
                mlsIdentityRepository = mlsIdentityRepository,
            ),
            wakeList = wakeList,
        ),
        contactRepository = contacts,
        profileKeyPull = pull,
    )

    private val group = byteArrayOf(4, 5, 6)

    /** The safety number the user compared with Alice and marked verified. */
    private val compared = "11111 22222 33333 44444 55555 66666 77777 88888 99999 00000 12345 67890"

    @Test
    fun `a new profile key from a contact replaces the one held`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        val newKey = generateSymmetricEncryptionKey()

        assertEquals(DirectMessageHandling.HANDLED, receive(from = ALICE, payload = ProfileKeyUpdate(newKey).serialize()))

        val alice = tables.contacts.getValue(ALICE)

        assertContentEquals(newKey, alice.profileEncryptionKey)
        assertFalse(alice.isBackedUp)
    }

    /**
     * Carol was never a contact, and Dave is one no longer: neither is somebody whose profile
     * this device has any business reading.
     */
    @Test
    fun `a new profile key from anybody but a contact changes nothing`() = runBlocking<Unit> {
        tables.add(contact(DAVE, key = aliceFirstKey).copy(isRemoved = true, isBackedUp = false))

        val update = ProfileKeyUpdate(generateSymmetricEncryptionKey()).serialize()

        assertEquals(DirectMessageHandling.HANDLED, receive(from = CAROL, payload = update))
        assertEquals(DirectMessageHandling.HANDLED, receive(from = DAVE, payload = update))

        assertNull(tables.contacts[CAROL])
        assertContentEquals(aliceFirstKey, tables.contacts.getValue(DAVE).profileEncryptionKey)
    }

    /** The user's key rotates on the user's own action only. */
    @Test
    fun `a contact's notice of removal never rotates the user's profile key`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        val pendingKey = generateSymmetricEncryptionKey()
        userVault.pendingKey = pendingKey

        receive(from = ALICE, payload = ContactRemoval.serialize())

        val alice = tables.contacts.getValue(ALICE)

        assertTrue(alice.isRemoved)
        assertFalse(alice.rotatesProfileKey)
        assertContentEquals(pendingKey, userVault.pendingKey)
    }

    /**
     * Her account is still there, and her name is only hidden from the contact list all the same,
     * for the next backup to keep in the vault in place of her entry: the key to her profile goes
     * with the contact she no longer is.
     */
    @Test
    fun `a contact's notice of removal forgets them and keeps their name`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        tables.names[ALICE] = "Alice Martin"

        receive(from = ALICE, payload = ContactRemoval.serialize())

        assertTrue(tables.contacts.getValue(ALICE).isRemoved)
        assertNull(tables.names[ALICE])
        assertEquals("Alice Martin", tables.hidden[ALICE])
    }

    /**
     * A message to Alice found her account deleted: she is forgotten as if her notice of removal
     * had come in, her row of a missing key going with her, and the user's key is left as it is.
     * Her name is only hidden from the contact list, for the next backup to keep in the vault in
     * place of her entry: the server deleted her profile with the account.
     */
    @Test
    fun `a contact who deleted their account is forgotten without a rotation and keeps their name`() =
        runBlocking<Unit> {
            tables.add(contact(ALICE, key = aliceFirstKey))
            tables.names[ALICE] = "Alice Martin"
            missing(ALICE)
            val pendingKey = generateSymmetricEncryptionKey()
            userVault.pendingKey = pendingKey

            contacts.receiveAccountDeletion(userUuid = ALICE)

            val alice = tables.contacts.getValue(ALICE)

            assertTrue(alice.isRemoved)
            assertFalse(alice.rotatesProfileKey)
            assertFalse(alice.isBackedUp)
            assertNull(tables.names[ALICE])
            assertEquals("Alice Martin", tables.userDao.getFullNameEvenIfHidden(uuid = ALICE))
            assertTrue(tables.pending.isEmpty())
            assertContentEquals(pendingKey, userVault.pendingKey)
        }

    /**
     * Dave is a contact no longer, his removal still waiting for the rotation it owes, and Carol
     * never was one: what the server says of their accounts changes nothing here.
     */
    @Test
    fun `an account deletion of anybody but a contact changes nothing`() = runBlocking<Unit> {
        tables.add(contact(DAVE, key = aliceFirstKey).copy(isRemoved = true, isBackedUp = false, rotatesProfileKey = true))

        contacts.receiveAccountDeletion(userUuid = DAVE)
        contacts.receiveAccountDeletion(userUuid = CAROL)

        assertTrue(tables.contacts.getValue(DAVE).rotatesProfileKey)
        assertNull(tables.contacts[CAROL])
    }

    /**
     * Alice's app told the user as her account went, before any message to her could find it out.
     * Her deletion may yet fail and leave her account holding the user's key, which stays as it is
     * all the same: it rotates on the user's own action only.
     */
    @Test
    fun `a contact's notice of their account deletion forgets them and keeps their name`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        tables.names[ALICE] = "Alice Martin"
        val pendingKey = generateSymmetricEncryptionKey()
        userVault.pendingKey = pendingKey

        assertEquals(DirectMessageHandling.HANDLED, receive(from = ALICE, payload = AccountDeletionNotice.serialize()))

        val alice = tables.contacts.getValue(ALICE)

        assertTrue(alice.isRemoved)
        assertFalse(alice.rotatesProfileKey)
        assertNull(tables.names[ALICE])
        assertEquals("Alice Martin", tables.hidden[ALICE])
        assertContentEquals(pendingKey, userVault.pendingKey)
    }

    /**
     * Alice deleted her account, Carol removed the user, the user removed Dave, and Erin deleted
     * her account too. Forgetting them hid their names from the contact list, and the backup keeps
     * each all the same, Dave's only once the key he holds has rotated. This device never read
     * Erin's profile, so nothing is kept of her.
     */
    @Test
    fun `a backup keeps the hidden name of every former contact`() = runBlocking<Unit> {
        listOf(ALICE, CAROL, DAVE, ERIN).forEach { tables.add(contact(it, key = aliceFirstKey)) }
        tables.names[ALICE] = "Alice Martin"
        tables.names[CAROL] = "Carol Petit"
        tables.names[DAVE] = "Dave Moreau"

        contacts.receiveAccountDeletion(userUuid = ALICE)
        receive(from = CAROL, payload = ContactRemoval.serialize())
        assertIs<Result.Success<Unit>>(contacts.remove(userUuid = DAVE))
        contacts.receiveAccountDeletion(userUuid = ERIN)

        val pending = PendingBackUp(contacts = tables.contactDao.getNotBackedUp())

        assertTrue(tables.names.isEmpty())
        assertEquals(
            mapOf(ALICE to "Alice Martin", CAROL to "Carol Petit", ERIN to null),
            contacts.namesKept(removals = pending.removals(isProfileKeyRotated = false)),
        )
        assertEquals(
            mapOf(ALICE to "Alice Martin", CAROL to "Carol Petit", DAVE to "Dave Moreau", ERIN to null),
            contacts.namesKept(removals = pending.removals(isProfileKeyRotated = true)),
        )
    }

    /**
     * Carol was never a contact, and Dave is one no longer: neither can have this device keep
     * anything of them, nor a tombstone marked anew, which would skip the rotation it waits for.
     */
    @Test
    fun `a notice of account deletion from anybody but a contact changes nothing`() = runBlocking<Unit> {
        tables.add(contact(DAVE, key = aliceFirstKey).copy(isRemoved = true, isBackedUp = false, rotatesProfileKey = true))

        assertEquals(DirectMessageHandling.HANDLED, receive(from = CAROL, payload = AccountDeletionNotice.serialize()))
        assertEquals(DirectMessageHandling.HANDLED, receive(from = DAVE, payload = AccountDeletionNotice.serialize()))

        assertNull(tables.contacts[CAROL])
        assertTrue(tables.contacts.getValue(DAVE).rotatesProfileKey)
    }

    /**
     * Alice deleted her account and Carol removed the user, and the vault holds nothing of either
     * but their name: each comes back hidden, for the expenses they share with the user to show,
     * and neither is a contact. Dave comes back a contact, and nothing else, his name for the read
     * of his profile to tell.
     */
    @Test
    fun `every former contact comes back as a hidden name and no contact`() = runBlocking<Unit> {
        val daveKey = generateSymmetricEncryptionKey()
        val book = ContactBook.EMPTY
            .with(
                userUuid = DAVE,
                contact = VaultContact(
                    profileEncryptionKey = daveKey,
                    mlsSignaturePublicKey = null,
                    handedProfileKeyHash = null,
                    verifiedSafetyNumber = null,
                ),
            )!!
            .withFormerContact(userUuid = ALICE, fullName = "Alice Martin")
            .withFormerContact(userUuid = CAROL, fullName = "Carol Petit")

        contacts.putBack(book = book)

        assertEquals(setOf(DAVE), tables.contacts.keys)
        assertTrue(tables.contacts.getValue(DAVE).isBackedUp)
        assertTrue(tables.names.isEmpty())
        assertEquals(mapOf(ALICE to "Alice Martin", CAROL to "Carol Petit"), tables.hidden)
        assertEquals(listOf(DAVE), tables.contactDao.getDueForRead().map { it.userUuid })
    }

    /**
     * The key a rotation left pending may already be the vault's, its write having landed with
     * the answer lost, and been handed to Alice: kept, the rotation her removal calls for would
     * find the vault holding it and take itself for done.
     */
    @Test
    fun `removing a contact owes a new key and never the one a rotation left pending`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        val leftPending = generateSymmetricEncryptionKey()
        userVault.pendingKey = leftPending

        assertIs<Result.Success<Unit>>(contacts.remove(userUuid = ALICE))

        val alice = tables.contacts.getValue(ALICE)

        assertTrue(alice.isRemoved)
        assertTrue(alice.rotatesProfileKey)
        assertFalse(leftPending.contentEquals(assertNotNull(userVault.pendingKey)))
    }

    /**
     * The device locked while the removal waited on a rotation of the key under way, so the key it
     * owes cannot be kept. Nothing has gone out to Alice yet, not even her channel looked up: she
     * keeps the user, who is told so rather than have the app end, and can try again. Told first,
     * she would have dropped the user with this device still holding her as a contact, handing her
     * every key after.
     */
    @Test
    fun `a removal whose owed key cannot be kept tells the contact nothing and writes nothing down`() =
        runBlocking<Unit> {
            tables.add(contact(ALICE, key = aliceFirstKey))
            val channels = ChannelTo(peerUuid = ALICE, mlsGroupId = group)
            userVault.isDeviceLocked = true

            val removal = contactsWith(directChannelDao = channels).remove(userUuid = ALICE)

            assertEquals(RemoveContactError.DeviceLocked, assertIs<Result.Error<RemoveContactError>>(removal).error)
            assertTrue(channels.lookedUp.isEmpty())
            assertFalse(tables.contacts.getValue(ALICE).isRemoved)
            assertNull(userVault.pendingKey)

            userVault.isDeviceLocked = false

            assertIs<Result.Success<Unit>>(contacts.remove(userUuid = ALICE))
            assertTrue(tables.contacts.getValue(ALICE).rotatesProfileKey)
            assertNotNull(userVault.pendingKey)
        }

    /**
     * Alice is removed with no group left to tell her in, and no backup has rotated the key since:
     * she holds the one the vault does, and the user's edit goes up under the new key instead.
     * What she reads stays as it stood when she was removed.
     */
    @Test
    fun `an edit made before a removal's rotation goes up under the key it owes`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(profiles.uploadProfile(userUuid = USER, username = "user", fullName = "Noa Leroy"))
        tables.add(contact(ALICE, key = aliceFirstKey))

        assertIs<Result.Success<Unit>>(contacts.remove(userUuid = ALICE))

        val owedKey = assertNotNull(userVault.pendingKey)

        assertIs<Result.Success<Unit>>(profiles.uploadProfile(userUuid = USER, username = "user", fullName = "Noa Petit"))

        assertEquals("Noa Leroy", nameOfUserUnder(key = userFirstKey))
        assertEquals("Noa Petit", nameOfUserUnder(key = owedKey))
        assertContentEquals(owedKey, userVault.profileEncryptionKey)
        assertTrue(tables.contacts.getValue(ALICE).rotatesProfileKey)
    }

    /**
     * Alice removed the user from a device holding no group to tell them in, so the user still
     * has her, then sent a request the user accepted: she is made a contact again with the
     * identity pinned for her, and the number the user compared is still the one computed.
     */
    @Test
    fun `a contact made one again keeps the safety number the user verified`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey).copy(verifiedSafetyNumber = compared))
        val newKey = generateSymmetricEncryptionKey()

        contacts.add(userUuid = ALICE, profileEncryptionKey = newKey, handedProfileKeyHash = null, fullName = null)

        val alice = tables.contacts.getValue(ALICE)

        assertEquals(compared, alice.verifiedSafetyNumber)
        assertContentEquals(newKey, alice.profileEncryptionKey)
        assertFalse(alice.isBackedUp)
    }

    /**
     * The vault keeps nothing of her but her name once her removal reaches it, so her tombstone's
     * mark goes too.
     */
    @Test
    fun `a former contact made one again starts over unverified`() = runBlocking<Unit> {
        tables.add(
            contact(ALICE, key = aliceFirstKey)
                .copy(verifiedSafetyNumber = compared, isRemoved = true, isBackedUp = false),
        )

        contacts.add(
            userUuid = ALICE,
            profileEncryptionKey = aliceFirstKey,
            handedProfileKeyHash = null,
            fullName = null,
        )

        val alice = tables.contacts.getValue(ALICE)

        assertFalse(alice.isRemoved)
        assertNull(alice.verifiedSafetyNumber)
    }

    /** Whoever removed whom. */
    @Test
    fun `a former contact is no longer asked for their key`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        tables.add(contact(DAVE, key = aliceFirstKey))
        missing(ALICE)
        missing(DAVE)

        receive(from = ALICE, payload = ContactRemoval.serialize())
        contacts.remove(userUuid = DAVE)

        assertTrue(tables.pending.isEmpty())
    }

    /**
     * Her new key never reached the user: the name read off the version the first one opens is
     * the one she had when it was replaced, which beats none, and she is asked for the new one.
     */
    @Test
    fun `a key that opens only an older version still names the contact and has them asked`() = runBlocking<Unit> {
        aliceRotates()
        tables.add(contact(ALICE, key = aliceFirstKey))

        assertIs<Result.Success<Unit>>(contacts.resolveNames())

        assertEquals("Alice Martin", tables.names[ALICE])
        assertContentEquals(aliceFirstKey, tables.pending.getValue(ALICE).profileEncryptionKey)
    }

    /** Pruned since, or never stored. */
    @Test
    fun `a key that opens nothing has the contact asked`() = runBlocking<Unit> {
        aliceUploads()
        val unknown = generateSymmetricEncryptionKey()
        tables.add(contact(ALICE, key = unknown))

        assertIs<Result.Success<Unit>>(contacts.resolveNames())

        assertNull(tables.names[ALICE])
        assertContentEquals(unknown, tables.pending.getValue(ALICE).profileEncryptionKey)
    }

    @Test
    fun `a key that opens the latest version asks nothing`() = runBlocking<Unit> {
        aliceUploads()
        tables.add(contact(ALICE, key = aliceFirstKey))

        contacts.resolveNames()

        assertEquals("Alice Martin", tables.names[ALICE])
        assertTrue(tables.pending.isEmpty())
    }

    /**
     * Read with the same key, the profile would only say the same again: what was tried stays,
     * and the backoff decides when she is asked next. Her new key coming in, the very next read
     * finds it opens her latest version, which settles it.
     */
    @Test
    fun `a contact missing a key is read again once a new one comes in and settled by it`() = runBlocking<Unit> {
        aliceRotates()
        tables.add(contact(ALICE, key = aliceFirstKey))
        contacts.resolveNames()
        tables.pending[ALICE] = tables.pending.getValue(ALICE).copy(lastRequestedAt = 1, requestCount = 3)

        assertTrue(tables.contactDao.getDueForRead().isEmpty())

        contacts.resolveNames()

        assertEquals(3, tables.pending.getValue(ALICE).requestCount)

        receive(from = ALICE, payload = ProfileKeyUpdate(aliceVault.profileEncryptionKey).serialize())

        assertEquals(listOf(ALICE), tables.contactDao.getDueForRead().map { it.userUuid })

        contacts.resolveNames()

        assertTrue(tables.pending.isEmpty())
        assertTrue(tables.contactDao.getDueForRead().isEmpty())
    }

    /**
     * No name to show for her, so she is read at every sync, as every contact with none is, her
     * key opening nothing or not: she may store her profile under it at any time. What was tried
     * stays all the same, and the backoff decides when she is asked next.
     */
    @Test
    fun `a contact with no name whose key opens nothing is read every sync and keeps what was tried`() =
        runBlocking<Unit> {
            aliceUploads()
            val unknown = generateSymmetricEncryptionKey()
            tables.add(contact(ALICE, key = unknown))
            contacts.resolveNames()
            tables.pending[ALICE] = tables.pending.getValue(ALICE).copy(lastRequestedAt = 1, requestCount = 3)

            assertEquals(listOf(ALICE), tables.contactDao.getDueForRead().map { it.userUuid })

            contacts.resolveNames()

            val row = tables.pending.getValue(ALICE)

            assertEquals(1L, row.lastRequestedAt)
            assertEquals(3, row.requestCount)
        }

    /** Still a key that opens no newest version: what was tried stays, read with the new one. */
    @Test
    fun `a new key that still opens no latest version keeps what was tried`() = runBlocking<Unit> {
        aliceRotates()
        tables.add(contact(ALICE, key = aliceFirstKey))
        contacts.resolveNames()
        tables.pending[ALICE] = tables.pending.getValue(ALICE).copy(lastRequestedAt = 1, requestCount = 3)

        val notHers = generateSymmetricEncryptionKey()
        receive(from = ALICE, payload = ProfileKeyUpdate(notHers).serialize())
        contacts.resolveNames()

        val row = tables.pending.getValue(ALICE)

        assertContentEquals(notHers, row.profileEncryptionKey)
        assertEquals(3, row.requestCount)
        assertTrue(tables.contactDao.getDueForRead().isEmpty())
    }

    /**
     * A rotation of Alice's cut short by a log-out left a version under a key she never held:
     * the one the user holds is hers as it stands all the same, and she says so, answering with
     * it. Asked again, she would only answer the same, every day.
     */
    @Test
    fun `a contact handing over the very key held settles it`() = runBlocking<Unit> {
        aliceUploads()
        aliceVault.failsReplacement = true
        aliceProfiles.oweProfileKeyRotation {}
        aliceProfiles.rotateProfileKey(userUuid = ALICE)
        aliceVault.pendingKey = null

        tables.add(contact(ALICE, key = aliceFirstKey))
        contacts.resolveNames()

        assertNotNull(tables.pending[ALICE])

        receive(from = ALICE, payload = ProfileKeyUpdate(aliceFirstKey).serialize())

        assertTrue(tables.pending.isEmpty())
        assertTrue(tables.contacts.getValue(ALICE).isBackedUp)
    }

    /** Nothing learnt about the key, so nothing recorded: the next sync reads it again. */
    @Test
    fun `a profile that could not be asked for is reported and records nothing`() = runBlocking<Unit> {
        aliceRotates()
        tables.add(contact(ALICE, key = aliceFirstKey))
        store.failsReading = true

        val read = contacts.resolveNames()

        assertEquals(
            ContactSyncError.Profile(ProfileError.Network(NetworkError.REQUEST_TIMEOUT)),
            assertIs<Result.Error<ContactSyncError>>(read).error,
        )
        assertTrue(tables.pending.isEmpty())
    }

    /**
     * Requests pile up while the user's device is away, and each answer spends one of the
     * contact's budget of replies: one answer a pass, in the group of the latest request, the one
     * they are in now. Anybody else's request is taken, and dropped.
     */
    @Test
    fun `a contact who asks is answered once a pass in the group of their latest request`() = runBlocking<Unit> {
        tables.add(contact(ALICE, key = aliceFirstKey))
        val latest = byteArrayOf(7, 8, 9)

        assertEquals(DirectMessageHandling.HANDLED, receive(from = ALICE, payload = ProfileKeyRequest.serialize()))
        receive(from = ALICE, payload = ProfileKeyRequest.serialize(), group = latest)
        assertEquals(DirectMessageHandling.HANDLED, receive(from = CAROL, payload = ProfileKeyRequest.serialize()))

        assertTrue(replies.isEmpty())

        handler.finishPass()

        val (recipient, replyGroup) = replies.single()

        assertEquals(ALICE, recipient)
        assertContentEquals(latest, replyGroup)

        handler.finishPass()

        assertEquals(1, replies.size)
    }

    private suspend fun receive(from: String, payload: ByteArray, group: ByteArray = this.group): DirectMessageHandling =
        handler.handle(senderUserUuid = from, mlsGroupId = group, payload = payload)

    /** Alice's profile, up under her first key. */
    private suspend fun aliceUploads() {
        assertIs<Result.Success<Unit>>(
            aliceProfiles.uploadProfile(userUuid = ALICE, username = "alice", fullName = "Alice Martin"),
        )
    }

    /** Alice's profile, up under her first key, then under the one a removal of hers replaced it with. */
    private suspend fun aliceRotates() {
        aliceUploads()

        aliceProfiles.oweProfileKeyRotation {}

        assertIs<Result.Success<Unit>>(aliceProfiles.rotateProfileKey(userUuid = ALICE))
        assertEquals(2, store.versionsOf(ALICE).size)
    }

    /** The name on the user's own profile, read off the version [key] seals. */
    private suspend fun nameOfUserUnder(key: ByteArray): String =
        assertIs<Result.Success<String>>(profiles.getFullName(userUuid = USER, profileEncryptionKey = key)).data

    private fun contact(userUuid: String, key: ByteArray) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = key,
            handedProfileKeyHash = null,
            verifiedSafetyNumber = null,
            isBackedUp = true,
        )

    private fun missing(userUuid: String) {
        tables.pending[userUuid] = PendingProfileKeyEntity(
            userUuid = userUuid,
            profileEncryptionKey = aliceFirstKey,
            lastRequestedAt = null,
        )
    }
}

/** The channel to [peerUuid], in the group [mlsGroupId], and whose channel was looked up. */
private class ChannelTo(
    private val peerUuid: String,
    private val mlsGroupId: ByteArray,
) : DirectChannelDao by NoChannels {

    val lookedUp = mutableListOf<String>()

    override suspend fun get(peerUuid: String): DirectChannelEntity? {
        lookedUp += peerUuid

        if (peerUuid != this.peerUuid) return null

        return DirectChannelEntity(
            peerUuid = peerUuid,
            mlsGroupId = mlsGroupId,
            previousMlsGroupId = null,
            olderMlsGroupId = null,
            pendingWelcome = null,
            pendingWelcomeSince = null,
            replyBudgetFullAt = null,
        )
    }
}

/** No contact request either way. Only the wake list is handed it, which keeps nothing here. */
private object NoRequests : ContactRequestDao {
    override fun getCountAsFlow(): Flow<Int> = error("Not used here")
    override fun getOpenedAsFlow(): Flow<List<OpenedContactRequest>> = error("Not used here")
    override suspend fun getOpenedOnce(): List<OpenedContactRequest> = error("Not used here")
    override suspend fun getOpened(uuid: String): OpenedContactRequest? = error("Not used here")
    override suspend fun getOpenedWithoutName(): List<OpenedContactRequest> = error("Not used here")
    override suspend fun getUnopened(): List<ContactRequestEntity> = error("Not used here")
    override suspend fun getAll(): List<ContactRequestEntity> = error("Not used here")
    override suspend fun get(uuid: String): ContactRequestEntity? = error("Not used here")
    override suspend fun insertNew(contactRequests: List<ContactRequestEntity>) = error("Not used here")
    override suspend fun delete(uuids: List<String>) = error("Not used here")

    override suspend fun markOpened(uuid: String, mlsGroupId: ByteArray, profileEncryptionKey: ByteArray) =
        error("Not used here")

    override suspend fun setSenderFullName(uuid: String, senderFullName: String) = error("Not used here")
    override suspend fun markAcceptanceSent(uuid: String, handedProfileKeyHash: String?) = error("Not used here")
    override suspend fun clearAcceptanceSent(uuid: String) = error("Not used here")

    override suspend fun getAccepted(senderUserUuid: String, mlsGroupId: ByteArray): OpenedContactRequest? =
        error("Not used here")

    override suspend fun getOutgoing(): List<OutgoingContactRequestEntity> = error("Not used here")
    override suspend fun getUnblockedOutgoing(): List<OutgoingContactRequestEntity> = error("Not used here")
    override suspend fun insertOutgoing(request: OutgoingContactRequestEntity) = error("Not used here")

    override suspend fun getOutgoing(recipientUserUuid: String, mlsGroupId: ByteArray): OutgoingContactRequestEntity? =
        error("Not used here")

    override suspend fun deleteOutgoing(recipientUserUuid: String, mlsGroupId: ByteArray) = error("Not used here")
    override suspend fun deleteOutgoingTo(recipientUserUuid: String) = error("Not used here")
}

/** Only ever handed to the HTTP client, for a session ending, which nothing here does. */
private object NoLocalData : LocalDataDao {
    override suspend fun deleteUsers() = error("Not used here")
    override suspend fun deleteExpenses() = error("Not used here")
    override suspend fun deleteCurrencies() = error("Not used here")
    override suspend fun deleteBeneficiaries() = error("Not used here")
    override suspend fun deleteGroups() = error("Not used here")
    override suspend fun deleteGroupMembers() = error("Not used here")
    override suspend fun deletePendingGroupCreations() = error("Not used here")
    override suspend fun deletePendingMembershipChanges() = error("Not used here")
    override suspend fun deleteDeclinedGroups() = error("Not used here")
    override suspend fun deleteContactRequests() = error("Not used here")
    override suspend fun deleteOutgoingContactRequests() = error("Not used here")
    override suspend fun deleteContacts() = error("Not used here")
    override suspend fun deleteDirectChannels() = error("Not used here")
    override suspend fun deleteIdentityConflicts() = error("Not used here")
    override suspend fun deleteOneTimeExpenseKeys() = error("Not used here")
    override suspend fun deleteUnverifiedExpenseKeys() = error("Not used here")
    override suspend fun deleteOneTimeExpenseRevisions() = error("Not used here")
    override suspend fun deleteOneTimeExpenseSettledMarks() = error("Not used here")
    override suspend fun deletePendingExpenseKeys() = error("Not used here")
    override suspend fun deleteKeyRequestBudgets() = error("Not used here")
    override suspend fun deletePendingProfileKeys() = error("Not used here")
    override suspend fun deleteProfileKeyRequestBudgets() = error("Not used here")
    override suspend fun deleteOwedExpenseKeys() = error("Not used here")
    override suspend fun deleteListedOneTimeExpenses() = error("Not used here")
    override suspend fun deleteChangeFeeds() = error("Not used here")
    override suspend fun deleteMlsRecords() = error("Not used here")
}
