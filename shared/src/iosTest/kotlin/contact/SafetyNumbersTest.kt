package contact

import database.data.local.dao.ContactTables
import database.data.local.dao.copy
import database.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import mls.MlsIdentityError
import uniffi.lib_duckcount.MlsClient
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val USER = "22222222-2222-2222-2222-222222222222"
private const val MALLORY = "33333333-3333-3333-3333-333333333333"

/**
 * What the user sees of the safety number of Alice, one of their contacts, with the database
 * stood in for by [ContactTables] and the MLS clients of both of them made through the library
 * itself, each pinning the other as becoming contacts does.
 */
class SafetyNumbersTest {

    private val tables = ContactTables()

    private val user = MlsClient(userUuid = USER, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())

    private val safetyNumbers = safetyNumbersOf(client = user)

    /** The number on Alice's phone, which the user reads theirs out against. */
    private val onAlicesPhone: String

    init {
        user.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
        alice.pinIdentity(userUuid = USER, signatureKey = user.signaturePublicKey())

        onAlicesPhone = assertNotNull(alice.safetyNumber(peerUserUuid = USER))
    }

    @Test
    fun `the user and the contact read out the same safety number`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        val shown = available(userUuid = ALICE)

        assertEquals(onAlicesPhone, shown.safetyNumber)
        assertFalse(shown.isVerified)
    }

    /** What the screen lays out, three lines of four groups. */
    @Test
    fun `a safety number is twelve groups of five digits`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        assertTrue(Regex("""\d{5}( \d{5}){11}""").matches(available(userUuid = ALICE).safetyNumber))
    }

    /** As a server handing over a key package of its own for Alice would leave the user. */
    @Test
    fun `a key substituted for the contact's shows a number that matches neither phone`() = runBlocking<Unit> {
        val mallory = MlsClient(userUuid = MALLORY, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
        val deceived = userWithoutPins()

        deceived.pinIdentity(userUuid = ALICE, signatureKey = mallory.signaturePublicKey())
        tables.add(contact(ALICE))

        val shown = assertIs<ContactVerification.Available>(safetyNumbersOf(client = deceived).observe(userUuid = ALICE).first())

        assertNotEquals(onAlicesPhone, shown.safetyNumber)
    }

    @Test
    fun `marking the number shown verifies the contact and has the vault take it`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        safetyNumbers.markVerified(userUuid = ALICE, safetyNumber = available(userUuid = ALICE).safetyNumber)

        val row = tables.contacts.getValue(ALICE)

        assertTrue(available(userUuid = ALICE).isVerified)
        assertEquals(onAlicesPhone, row.verifiedSafetyNumber)
        assertFalse(row.isBackedUp)
    }

    @Test
    fun `clearing the mark leaves the contact unverified and has the vault take it`() = runBlocking<Unit> {
        tables.add(contact(ALICE).copy(verifiedSafetyNumber = onAlicesPhone))

        safetyNumbers.clearMark(userUuid = ALICE)

        val row = tables.contacts.getValue(ALICE)

        assertFalse(available(userUuid = ALICE).isVerified)
        assertNull(row.verifiedSafetyNumber)
        assertFalse(row.isBackedUp)
    }

    /** As a mark made for identities other than the ones pinned now, restored from the vault. */
    @Test
    fun `a mark made for another number does not count`() = runBlocking<Unit> {
        val compared = "11111 22222 33333 44444 55555 66666 77777 88888 99999 00000 12345 67890"
        tables.add(contact(ALICE).copy(verifiedSafetyNumber = compared))

        assertFalse(available(userUuid = ALICE).isVerified)
    }

    @Test
    fun `a blocked contact shows no number even when marked`() = runBlocking<Unit> {
        tables.add(contact(ALICE).copy(verifiedSafetyNumber = onAlicesPhone))
        tables.blocked += ALICE

        assertEquals(ContactVerification.IdentityChanged, safetyNumbers.observe(userUuid = ALICE).first())
    }

    /** Mallory was never a contact, and Alice is one no longer. */
    @Test
    fun `somebody who is not a contact shows no number and is not marked`() = runBlocking<Unit> {
        tables.add(contact(ALICE).copy(isRemoved = true, isBackedUp = false))

        safetyNumbers.markVerified(userUuid = ALICE, safetyNumber = onAlicesPhone)

        assertEquals(ContactVerification.NotContact, safetyNumbers.observe(userUuid = ALICE).first())
        assertEquals(ContactVerification.NotContact, safetyNumbers.observe(userUuid = MALLORY).first())
        assertNull(tables.contacts.getValue(ALICE).verifiedSafetyNumber)
    }

    /** What a log-out emptying the database under the screen leaves, the row read just before. */
    @Test
    fun `a contact the client holds no pin for reads as no longer one`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        assertEquals(
            ContactVerification.NotContact,
            safetyNumbersOf(client = userWithoutPins()).observe(userUuid = ALICE).first(),
        )
    }

    @Test
    fun `a client that cannot be opened leaves the number unavailable`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        val unopened = SafetyNumbers(
            contactDao = tables.contactDao,
            client = { Result.Error(MlsIdentityError.MissingSession) },
        )

        assertEquals(
            ContactVerification.Unavailable(ContactVerificationError.Identity(MlsIdentityError.MissingSession)),
            unopened.observe(userUuid = ALICE).first(),
        )
    }

    /** The user's own identity in a store holding no pin, as a log-out leaves the client. */
    private fun userWithoutPins() =
        MlsClient.restore(
            userUuid = USER,
            secret = ByteArray(32) { 1 },
            store = InMemoryMlsStore(),
            signaturePrivateKey = user.signaturePrivateKey(),
            signaturePublicKey = user.signaturePublicKey(),
        )

    private fun safetyNumbersOf(client: MlsClient) =
        SafetyNumbers(contactDao = tables.contactDao, client = { Result.Success(client) })

    private suspend fun available(userUuid: String): ContactVerification.Available =
        assertIs<ContactVerification.Available>(safetyNumbers.observe(userUuid = userUuid).first())

    private fun contact(userUuid: String) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            handedProfileKeyHash = null,
            verifiedSafetyNumber = null,
            isBackedUp = true,
        )
}
