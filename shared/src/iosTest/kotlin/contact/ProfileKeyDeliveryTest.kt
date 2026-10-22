package contact

import channel.DirectChannelError
import database.data.local.dao.ContactDao
import database.data.local.entity.ContactEntity
import database.data.local.entity.VerifiableContact
import database.data.remote.MessageLimitScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import message.DirectSendError
import message.ResendsBooked
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.InMemoryProfileKeyVault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"

/**
 * Who the user's profile key is handed to, and what each outcome of sending it is taken for.
 * Alice, Bob and Carol are the user's contacts.
 */
class ProfileKeyDeliveryTest {

    private val olderKey = generateSymmetricEncryptionKey()
    private val currentKey = generateSymmetricEncryptionKey()

    private val olderHash = profileEncryptionKeyPartialHash(olderKey)
    private val currentHash = profileEncryptionKeyPartialHash(currentKey)

    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = currentKey)

    private val resends = ResendsBooked()

    /** Every contact holding the current key, which is how nearly every sync finds them. */
    @Test
    fun `nobody owed reads nothing from the vault and sends nothing`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = currentHash), contact(BOB, handed = currentHash))
        val sends = Sends()

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(0, vault.reads)
        assertTrue(sends.recipients.isEmpty())
    }

    /**
     * Alice still holds the key a removal replaced since, and nobody knows which one Bob holds:
     * both are handed the current one, read from the vault once for the two of them.
     */
    @Test
    fun `a contact handed an older key or none is handed the current one`() = runBlocking<Unit> {
        val contacts = ContactsOf(
            contact(ALICE, handed = olderHash),
            contact(BOB, handed = null),
            contact(CAROL, handed = currentHash),
        )
        val sends = Sends()

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(listOf(ALICE, BOB), sends.recipients)
        assertEquals(1, vault.reads)

        sends.payloads.forEach { payload ->
            assertContentEquals(currentKey, assertNotNull(ProfileKeyUpdate.parse(plaintext = payload)).profileEncryptionKey)
        }

        assertEquals(currentHash, contacts.handedTo(ALICE))
        assertEquals(currentHash, contacts.handedTo(BOB))
    }

    /** After a log-out, before anything read the key: the vault says which is current. */
    @Test
    fun `with no hash kept the vault is read once`() = runBlocking<Unit> {
        vault.cachedPartialHash = null

        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = currentHash))
        val sends = Sends()

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(1, vault.reads)
        assertEquals(listOf(ALICE), sends.recipients)
    }

    /**
     * The hash kept named the older key when the pass looked, and the key rotated before it read
     * the key itself: Alice is recorded as handed the one that went out, never the one named.
     */
    @Test
    fun `what a contact is recorded as handed is the key that went out`() = runBlocking<Unit> {
        vault.cachedPartialHash = olderHash

        val contacts = ContactsOf(contact(ALICE, handed = null))

        delivery(contacts, Sends()).handOut()

        assertEquals(currentHash, contacts.handedTo(ALICE))
    }

    /** The server offers no way to reach them: nobody to hand it to. */
    @Test
    fun `a contact who cannot be reached counts as handed the key`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash))
        val sends = Sends(ALICE to Result.Error(DirectSendError.Channel(DirectChannelError.Unreachable)))

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(currentHash, contacts.handedTo(ALICE))
    }

    /**
     * The send forgot Alice as it came back, see [DirectSendError.RecipientDeleted]: nothing is
     * recorded of a key a former contact holds, and that is no failure.
     */
    @Test
    fun `a contact who deleted their account is no failure`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = olderHash))
        val sends = Sends(ALICE to Result.Error(DirectSendError.RecipientDeleted))

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(currentHash, contacts.handedTo(BOB))
    }

    /**
     * The proxy answers a `404` while the API is being redeployed, so it says nothing of Alice's
     * account: she is still owed the key, and handed it at the next pass, as after any other
     * failure. An account an administrator removed answers one for good, which no retry of the
     * sync changes, so it is not reported.
     */
    @Test
    fun `a 404 is not reported and the contact handed the key again next time`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = olderHash))
        val sends = Sends(ALICE to Result.Error(DirectSendError.Network(NetworkError.NOT_FOUND)))

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(currentHash, contacts.handedTo(BOB))
    }

    /** No retry changes either, so the key waits, and the others are handed theirs meanwhile. */
    @Test
    fun `a contact not pinned or whose identity is in doubt waits`() = runBlocking<Unit> {
        val contacts = ContactsOf(
            contact(ALICE, handed = olderHash),
            contact(BOB, handed = olderHash),
            contact(CAROL, handed = olderHash),
        )
        val sends = Sends(
            ALICE to Result.Error(DirectSendError.Channel(DirectChannelError.NotPinned)),
            BOB to Result.Error(DirectSendError.Channel(DirectChannelError.IdentityChanged)),
        )

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(olderHash, contacts.handedTo(BOB))
        assertEquals(currentHash, contacts.handedTo(CAROL))
    }

    /** Sending more only pushes the count further past the limit: the rest wait for it to reopen. */
    @Test
    fun `past the user's own limit the pass stops and is booked again for when it reopens`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = olderHash))
        val sends = Sends(
            ALICE to Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER)),
        )

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(listOf(ALICE), sends.recipients)
        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(olderHash, contacts.handedTo(BOB))
        assertEquals(listOf(45L), resends.seconds)
    }

    /** What the user may send Alice is spent, which costs nothing of what they may send Bob. */
    @Test
    fun `a contact past their own limit waits while the others are handed the key`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = olderHash))
        val sends = Sends(
            ALICE to Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 20, scope = MessageLimitScope.RECIPIENT)),
        )

        assertIs<Result.Success<Unit>>(delivery(contacts, sends).handOut())

        assertEquals(listOf(ALICE, BOB), sends.recipients)
        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(currentHash, contacts.handedTo(BOB))
        assertEquals(listOf(20L), resends.seconds)
    }

    @Test
    fun `any other failure is reported and the contact handed the key again next time`() = runBlocking<Unit> {
        val contacts = ContactsOf(contact(ALICE, handed = olderHash), contact(BOB, handed = olderHash))
        val sends = Sends(ALICE to Result.Error(DirectSendError.Network(NetworkError.REQUEST_TIMEOUT)))

        val handOut = delivery(contacts, sends).handOut()

        assertEquals(
            ContactSyncError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<ContactSyncError>>(handOut).error,
        )
        assertEquals(olderHash, contacts.handedTo(ALICE))
        assertEquals(currentHash, contacts.handedTo(BOB))
    }

    private fun delivery(contacts: ContactsOf, sends: Sends) =
        ProfileKeyDelivery(contactDao = contacts, profileKeyVault = vault, send = sends::send, resendScheduler = resends)

    private fun contact(userUuid: String, handed: String?) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            handedProfileKeyHash = handed,
            verifiedSafetyNumber = null,
            isBackedUp = true,
        )
}

/** Sends nothing anywhere, answering each recipient as told and every other one with success. */
private class Sends(vararg outcomes: Pair<String, EmptyResult<DirectSendError>>) {
    private val outcomes = outcomes.toMap()

    val recipients = mutableListOf<String>()
    val payloads = mutableListOf<ByteArray>()

    suspend fun send(recipientUserUuid: String, payload: ByteArray): EmptyResult<DirectSendError> {
        recipients += recipientUserUuid
        payloads += payload

        return outcomes[recipientUserUuid] ?: Result.Success(Unit)
    }
}

/**
 * The user's contacts as the database would hold them, none removed or blocked, which the
 * query the pass reads them with leaves out.
 */
private class ContactsOf(vararg contacts: ContactEntity) : ContactDao {
    private val rows = contacts.associateByTo(LinkedHashMap()) { it.userUuid }

    fun handedTo(userUuid: String): String? = rows.getValue(userUuid).handedProfileKeyHash

    override suspend fun getUnblocked(): List<ContactEntity> = rows.values.toList()

    override suspend fun setHandedProfileKeyHash(userUuid: String, handedProfileKeyHash: String) {
        val row = rows.getValue(userUuid)

        rows[userUuid] = ContactEntity(
            userUuid = row.userUuid,
            profileEncryptionKey = row.profileEncryptionKey,
            handedProfileKeyHash = handedProfileKeyHash,
            verifiedSafetyNumber = row.verifiedSafetyNumber,
            isBackedUp = false,
            isRemoved = row.isRemoved,
            rotatesProfileKey = row.rotatesProfileKey,
        )
    }

    override suspend fun upsert(contact: ContactEntity) = error("Not used here")
    override suspend fun insertNew(contacts: List<ContactEntity>) = error("Not used here")
    override suspend fun get(userUuid: String): ContactEntity? = error("Not used here")
    override suspend fun getUserUuids(): List<String> = error("Not used here")
    override suspend fun getUnblockedUserUuids(): List<String> = error("Not used here")
    override suspend fun getNotBackedUp(): List<ContactEntity> = error("Not used here")

    override suspend fun markBackedUp(
        userUuid: String,
        profileEncryptionKey: ByteArray,
        handedProfileKeyHash: String?,
        verifiedSafetyNumber: String?,
    ) = error("Not used here")

    override suspend fun markRemoved(userUuid: String, rotatesProfileKey: Boolean) = error("Not used here")
    override suspend fun clearProfileKeyRotation(userUuids: List<String>) = error("Not used here")

    override suspend fun replaceProfileEncryptionKey(userUuid: String, profileEncryptionKey: ByteArray) =
        error("Not used here")

    override suspend fun deleteRemoved(userUuids: List<String>) = error("Not used here")
    override suspend fun getDueForRead(): List<ContactEntity> = error("Not used here")
    override suspend fun isUnblocked(userUuid: String): Boolean = error("Not used here")
    override fun getVerifiableAsFlow(userUuid: String): Flow<VerifiableContact?> = error("Not used here")

    override suspend fun setVerifiedSafetyNumber(userUuid: String, verifiedSafetyNumber: String?) =
        error("Not used here")
}
