package expense

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OneTimeExpensePayloadTest {

    private val key = ByteArray(32) { it.toByte() }
    private val expenseUuid = "11111111-1111-1111-1111-111111111111"
    private val beneficiaries = listOf(
        "22222222-2222-2222-2222-222222222222",
        "33333333-3333-3333-3333-333333333333",
    )

    @Test
    fun `a key share survives being encrypted and read back`() {
        val share = ExpenseKeyShare(
            keys = listOf(
                ExpenseKey(expenseUuid = expenseUuid, key = key, beneficiaryUuids = beneficiaries),
            ),
        )

        val reread = ExpenseKeyShare.parse(plaintext = share.serialize())

        assertNotNull(reread)
        assertEquals(expenseUuid, reread.keys.single().expenseUuid)
        assertContentEquals(key, reread.keys.single().key)
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
            keys = listOf(ExpenseKey(expenseUuid, key, beneficiaries)),
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
            {"type":"one_time_expense_key","keys":[{"expense_uuid":"$expenseUuid","key":"AQID","beneficiaries":[],"note":"later"}],"sent_at":"ignored"}
        """.trimIndent().encodeToByteArray()

        assertNotNull(ExpenseKeyShare.parse(plaintext = withExtra))
    }

    /**
     * A share written before beneficiaries travelled with the key still reads. It leaves
     * the recipient unable to answer anyone else's request for that expense, which is the
     * honest outcome: they were never told who it was for.
     */
    @Test
    fun `a share without beneficiaries still reads`() {
        val old = """{"type":"one_time_expense_key","keys":[{"expense_uuid":"$expenseUuid","key":"AQID"}]}"""

        val reread = ExpenseKeyShare.parse(plaintext = old.encodeToByteArray())

        assertNotNull(reread)
        assertEquals(emptyList(), reread.keys.single().beneficiaryUuids)
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

    @Test
    fun `plaintext that is neither reads as none`() {
        assertNull(ExpenseKeyShare.parse(plaintext = "not a payload".encodeToByteArray()))
        assertNull(ExpenseKeyRequest.parse(plaintext = "{}".encodeToByteArray()))
    }
}
