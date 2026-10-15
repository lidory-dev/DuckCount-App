package expense

import uniffi.lib_duckcount.OneTimeExpenseId
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newOneTimeExpenseId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "01234567-89ab-4cde-8f01-23456789abcd"
private const val BOB = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "66666666-6666-6666-6666-666666666666"

/**
 * Which keys of a share Bob keeps from Alice, with whether she is the creditor checked by the
 * library itself, see [recordsFrom].
 */
class ExpenseKeyShareReceiptTest {

    /** The signature key MLS authenticated the share's sender with. */
    private val signatureKey = ByteArray(32) { 7 }

    /** Created while Alice was Bob's contact, and still without its key on his device when she stopped being one. */
    private val owed = assertNotNull(newOneTimeExpenseId(creditorUuid = ALICE))

    /** One Alice put Bob on after she stopped being his contact. */
    private val listedSince = assertNotNull(newOneTimeExpenseId(creditorUuid = ALICE))

    private val contact = ExpectedKeys(isContact = true, awaitedAtRemoval = emptySet())
    private val formerContact = ExpectedKeys(isContact = false, awaitedAtRemoval = setOf(owed.uuid))

    @Test
    fun `a former contact's key to an expense awaited when they stopped being one is kept`() {
        val record = shareOf(owed).recordsFrom(
            senderUuid = ALICE,
            senderSignatureKey = signatureKey,
            expected = formerContact,
        ).single()

        assertEquals(owed.uuid, record.expenseUuid)
        assertEquals(ALICE, record.creditorUuid)
        assertContentEquals(owed.nonce, record.nonce)
        assertContentEquals(signatureKey, record.creditorSignatureKey)
        assertEquals(listOf(BOB), record.beneficiaryUuids)
    }

    @Test
    fun `a former contact's key to an expense listed since is dropped`() {
        val kept = shareOf(owed, listedSince).recordsFrom(
            senderUuid = ALICE,
            senderSignatureKey = signatureKey,
            expected = formerContact,
        )

        assertEquals(listOf(owed.uuid), kept.map { it.expenseUuid })
    }

    @Test
    fun `a contact's keys are kept as before`() {
        val kept = shareOf(owed, listedSince).recordsFrom(
            senderUuid = ALICE,
            senderSignatureKey = signatureKey,
            expected = contact,
        )

        assertEquals(listOf(owed.uuid, listedSince.uuid), kept.map { it.expenseUuid })
    }

    /** A contact handing over thirty-two bytes of their choosing as the key to Alice's expense. */
    @Test
    fun `a key whose nonce shows somebody else to be the creditor is dropped even from a contact`() {
        val kept = shareOf(owed).recordsFrom(
            senderUuid = MALLORY,
            senderSignatureKey = signatureKey,
            expected = contact,
        )

        assertTrue(kept.isEmpty())
    }

    private fun shareOf(vararg ids: OneTimeExpenseId) = ExpenseKeyShare(
        keys = ids.map {
            ExpenseKey(
                expenseUuid = it.uuid,
                key = generateSymmetricEncryptionKey(),
                nonce = it.nonce,
                beneficiaryUuids = listOf(BOB),
            )
        },
    )
}
