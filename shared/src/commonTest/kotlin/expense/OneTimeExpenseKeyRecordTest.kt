package expense

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EXPENSE = "11111111-1111-1111-1111-111111111111"
private const val CREDITOR = "22222222-2222-2222-2222-222222222222"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "44444444-4444-4444-4444-444444444444"
private const val STRANGER = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "66666666-6666-6666-6666-666666666666"

class OneTimeExpenseKeyRecordTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(16) { (it + 50).toByte() }
    private val creditorSignatureKey = ByteArray(32) { (it + 100).toByte() }

    private fun record(
        expenseUuid: String = EXPENSE,
        creditorUuid: String = CREDITOR,
        beneficiaryUuids: List<String> = listOf(BOB, CAROL),
    ) = OneTimeExpenseKeyRecord(
        expenseUuid = expenseUuid,
        key = key,
        creditorUuid = creditorUuid,
        nonce = nonce,
        creditorSignatureKey = creditorSignatureKey,
        beneficiaryUuids = beneficiaryUuids,
    )

    @Test
    fun `a record survives being sealed and read back`() {
        val reread = OneTimeExpenseKeyRecord.parse(
            plaintext = record().serialize(),
            expenseUuid = EXPENSE,
        )

        assertNotNull(reread)
        assertEquals(EXPENSE, reread.expenseUuid)
        assertContentEquals(key, reread.key)
        assertEquals(CREDITOR, reread.creditorUuid)
        assertContentEquals(nonce, reread.nonce)
        assertContentEquals(creditorSignatureKey, reread.creditorSignatureKey)
        assertEquals(listOf(BOB, CAROL), reread.beneficiaryUuids)
    }

    /**
     * The one thing stopping a server from moving a copy to another expense and having the
     * creditor unwrap it for somebody who has no claim to that expense.
     */
    @Test
    fun `a record that came with another expense is refused`() {
        assertNull(
            OneTimeExpenseKeyRecord.parse(
                plaintext = record().serialize(),
                expenseUuid = "99999999-9999-9999-9999-999999999999",
            ),
        )
    }

    @Test
    fun `beneficiaries are written in a fixed order so two devices write the same bytes`() {
        val oneWay = record(beneficiaryUuids = listOf(BOB, CAROL)).serialize()
        val theOther = record(beneficiaryUuids = listOf(CAROL, BOB)).serialize()

        assertContentEquals(oneWay, theOther)
    }

    @Test
    fun `plaintext that is not a record reads as none`() {
        assertNull(OneTimeExpenseKeyRecord.parse("not a record".encodeToByteArray(), EXPENSE))
        assertNull(OneTimeExpenseKeyRecord.parse("{}".encodeToByteArray(), EXPENSE))
    }

    /** A record from a build that changed the shape is not guessed at. */
    @Test
    fun `a record of an unknown version reads as none`() {
        val future =
            """{"v":2,"expense":"$EXPENSE","key":"AAAA","creditor":"$CREDITOR","nonce":"AAAA","creditor_key":"AAAA","beneficiaries":[]}"""

        assertNull(OneTimeExpenseKeyRecord.parse(future.encodeToByteArray(), EXPENSE))
    }

    /**
     * Without the nonce, the creditor could hand the key over to nobody who would take it, so
     * a record that has none is not one.
     */
    @Test
    fun `a record without a nonce reads as none`() {
        val withoutNonce =
            """{"v":1,"expense":"$EXPENSE","key":"AAAA","creditor":"$CREDITOR","creditor_key":"AAAA","beneficiaries":[]}"""

        assertNull(OneTimeExpenseKeyRecord.parse(withoutNonce.encodeToByteArray(), EXPENSE))
    }

    /** An edit changes who an expense is for, never its key or whose it is. */
    @Test
    fun `a record meant for other beneficiaries keeps its key and its creditor`() {
        val edited = record(beneficiaryUuids = listOf(BOB, CAROL))
            .withBeneficiaries(beneficiaryUuids = listOf(CAROL, DAVE))

        assertEquals(EXPENSE, edited.expenseUuid)
        assertContentEquals(key, edited.key)
        assertEquals(CREDITOR, edited.creditorUuid)
        assertContentEquals(nonce, edited.nonce)
        assertContentEquals(creditorSignatureKey, edited.creditorSignatureKey)
        assertEquals(listOf(CAROL, DAVE), edited.beneficiaryUuids)
    }

    @Test
    fun `the creditor and the beneficiaries are participants and nobody else is`() {
        val record = record()

        assertTrue(record.isParticipant(userUuid = CREDITOR))
        assertTrue(record.isParticipant(userUuid = BOB))
        assertTrue(record.isParticipant(userUuid = CAROL))
        assertFalse(record.isParticipant(userUuid = STRANGER))
    }

    @Test
    fun `the creditor hands the key to somebody the record names`() {
        val answer = record().keyFor(requesterUuid = BOB, ownUserUuid = CREDITOR)

        assertNotNull(answer)
        assertEquals(EXPENSE, answer.expenseUuid)
        assertContentEquals(key, answer.key)
        assertContentEquals(nonce, answer.nonce)
    }

    /**
     * The attack this exists for: the server adds a beneficiary row of its own and asks the
     * creditor for the key. The record the creditor wrote does not name them, so there is
     * no answer, whatever the server's own listing says.
     */
    @Test
    fun `somebody the record does not name gets nothing`() {
        assertNull(record().keyFor(requesterUuid = STRANGER, ownUserUuid = CREDITOR))
    }

    /** A key is the creditor's to give. A beneficiary holding one does not pass it on. */
    @Test
    fun `a holder who is not the creditor gives out nothing`() {
        assertNull(record().keyFor(requesterUuid = CAROL, ownUserUuid = BOB))
    }
}
