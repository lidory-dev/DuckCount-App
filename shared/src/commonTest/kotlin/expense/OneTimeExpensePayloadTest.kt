package expense

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OneTimeExpensePayloadTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(16) { (it + 50).toByte() }
    private val expenseUuid = "11111111-1111-1111-1111-111111111111"
    private val beneficiaries = listOf(
        "22222222-2222-2222-2222-222222222222",
        "33333333-3333-3333-3333-333333333333",
    )

    @Test
    fun `a key share survives being encrypted and read back`() {
        val share = ExpenseKeyShare(
            keys = listOf(
                ExpenseKey(expenseUuid = expenseUuid, key = key, nonce = nonce, beneficiaryUuids = beneficiaries),
            ),
        )

        val reread = ExpenseKeyShare.parse(plaintext = share.serialize())

        assertNotNull(reread)
        assertEquals(expenseUuid, reread.keys.single().expenseUuid)
        assertContentEquals(key, reread.keys.single().key)
        assertContentEquals(nonce, reread.keys.single().nonce)
        assertEquals(beneficiaries, reread.keys.single().beneficiaryUuids)
    }

    @Test
    fun `a key request survives being encrypted and read back`() {
        val request = ExpenseKeyRequest(expenseUuids = listOf(expenseUuid))

        val reread = ExpenseKeyRequest.parse(plaintext = request.serialize())

        assertNotNull(reread)
        assertEquals(listOf(expenseUuid), reread.expenseUuids)
    }

    /**
     * Every payload on this channel travels the same way, so none may ever be taken for
     * another: a request read as a share would store nothing and ask for nothing again.
     */
    @Test
    fun `a share and a request are never taken for one another`() {
        val share = ExpenseKeyShare(
            keys = listOf(
                ExpenseKey(expenseUuid = expenseUuid, key = key, nonce = nonce, beneficiaryUuids = beneficiaries),
            ),
        ).serialize()

        val request = ExpenseKeyRequest(expenseUuids = listOf(expenseUuid)).serialize()

        assertNull(ExpenseKeyRequest.parse(plaintext = share))
        assertNull(ExpenseKeyShare.parse(plaintext = request))
    }

    /** Both are read off the same channel as contact payloads, which must not collide. */
    @Test
    fun `a contact payload is neither a share nor a request`() {
        val card = """{"profile_encryption_key":"AQID"}""".encodeToByteArray()
        val removal = """{"type":"contact_removal"}""".encodeToByteArray()

        assertNull(ExpenseKeyShare.parse(plaintext = card))
        assertNull(ExpenseKeyRequest.parse(plaintext = card))
        assertNull(ExpenseKeyShare.parse(plaintext = removal))
        assertNull(ExpenseKeyRequest.parse(plaintext = removal))
    }

    @Test
    fun `a field a newer build added does not make a payload unreadable`() {
        val withExtra = """
            {"type":"one_time_expense_key","keys":[{"expense_uuid":"$expenseUuid","key":"AQID","nonce":"AQID","beneficiaries":[],"note":"later"}],"sent_at":"ignored"}
        """.trimIndent().encodeToByteArray()

        assertNotNull(ExpenseKeyShare.parse(plaintext = withExtra))
    }

    /** A request naming more than can be answered is cut rather than refused. */
    @Test
    fun `a request is capped at what answering it can cost`() {
        val many = (1..ExpenseKeyRequest.MAX_EXPENSES + 50).map { "expense-$it" }

        val reread = ExpenseKeyRequest.parse(
            plaintext = ExpenseKeyRequest(expenseUuids = many).serialize(),
        )

        assertNotNull(reread)
        assertEquals(ExpenseKeyRequest.MAX_EXPENSES, reread.expenseUuids.size)
    }

    /**
     * A key a share carries can be kept until a listing says whether the user is on its
     * expense, so a share naming more than any sender puts in one is cut too.
     */
    @Test
    fun `a share is capped at what one request can ask for`() {
        val many = (1..ExpenseKeyShare.MAX_KEYS + 50).map {
            ExpenseKey(expenseUuid = "expense-$it", key = key, nonce = nonce, beneficiaryUuids = beneficiaries)
        }

        val reread = ExpenseKeyShare.parse(plaintext = ExpenseKeyShare(keys = many).serialize())

        assertNotNull(reread)
        assertEquals(many.take(ExpenseKeyShare.MAX_KEYS).map { it.expenseUuid }, reread.keys.map { it.expenseUuid })
    }

    /**
     * Without its nonce a key cannot show that whoever sent it is the expense's creditor, and
     * is not one the recipient could ever keep.
     */
    @Test
    fun `a share with a key that has no nonce reads as none`() {
        val withoutNonce =
            """{"type":"one_time_expense_key","keys":[{"expense_uuid":"$expenseUuid","key":"AQID","beneficiaries":[]}]}"""

        assertNull(ExpenseKeyShare.parse(plaintext = withoutNonce.encodeToByteArray()))
    }

    @Test
    fun `plaintext that is neither reads as none`() {
        assertNull(ExpenseKeyShare.parse(plaintext = "not a payload".encodeToByteArray()))
        assertNull(ExpenseKeyRequest.parse(plaintext = "{}".encodeToByteArray()))
    }
}
