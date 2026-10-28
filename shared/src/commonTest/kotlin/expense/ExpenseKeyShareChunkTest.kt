package expense

import app.lidory.duckcount.ExpenseLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The keys to hand somebody cut into shares the server takes one message of each, see
 * [ExpenseKeyShare.chunked], up to the hundred keys one request can ask for, of expenses with up
 * to a hundred beneficiaries each.
 */
class ExpenseKeyShareChunkTest {

    /** As many keys as a request asks for at most, each to an expense with as many beneficiaries as one may have. */
    private val largest = List(ExpenseKeyShare.MAX_KEYS) { keyOf(index = it, beneficiaryCount = MAX_BENEFICIARIES) }

    @Test
    fun `every key is in exactly one share and in order`() {
        val shares = ExpenseKeyShare(keys = largest).chunked()

        assertEquals(largest.map { it.expenseUuid }, shares.flatMap { share -> share.keys.map { it.expenseUuid } })
    }

    @Test
    fun `no share takes more than one may`() {
        ExpenseKeyShare(keys = largest).chunked().forEach { share ->
            assertTrue(share.serialize().size <= ExpenseKeyShare.MAX_PLAINTEXT_BYTES, "${share.serialize().size}")
        }
    }

    /** Each share is one of the sender's allowance, and one of the asker's budget when it answers one. */
    @Test
    fun `each share holds as many keys as fit`() {
        val shares = ExpenseKeyShare(keys = largest).chunked()

        shares.zipWithNext().forEach { (share, next) ->
            val grown = ExpenseKeyShare(keys = share.keys + next.keys.first()).serialize().size

            assertTrue(grown > ExpenseKeyShare.MAX_PLAINTEXT_BYTES, "$grown")
        }
        assertEquals(5, shares.size)
    }

    @Test
    fun `a key of an expense with as many beneficiaries as one may have fits in one share`() {
        val key = keyOf(index = 0, beneficiaryCount = MAX_BENEFICIARIES)

        val shares = ExpenseKeyShare(keys = listOf(key)).chunked()

        assertEquals(1, shares.size)
        assertTrue(shares.single().serialize().size * 20 < ExpenseKeyShare.MAX_PLAINTEXT_BYTES)
    }

    @Test
    fun `keys that fit in one share go in one`() {
        val keys = List(ExpenseKeyShare.MAX_KEYS) { keyOf(index = it, beneficiaryCount = 2) }

        val shares = ExpenseKeyShare(keys = keys).chunked()

        assertEquals(1, shares.size)
        assertEquals(keys.map { it.expenseUuid }, shares.single().keys.map { it.expenseUuid })
    }

    @Test
    fun `nothing to hand over is no share`() {
        assertTrue(ExpenseKeyShare(keys = emptyList()).chunked().isEmpty())
    }

    /** A recipient reads each share on its own. */
    @Test
    fun `every share reads back as one`() {
        ExpenseKeyShare(keys = largest).chunked().forEach { share ->
            val reread = assertNotNull(ExpenseKeyShare.parse(plaintext = share.serialize()))

            assertEquals(share.keys.map { it.expenseUuid }, reread.keys.map { it.expenseUuid })
            assertEquals(share.keys.map { it.beneficiaryUuids.sorted() }, reread.keys.map { it.beneficiaryUuids })
        }
    }

    private fun keyOf(index: Int, beneficiaryCount: Int) =
        ExpenseKey(
            expenseUuid = "${index.toString().padStart(8, '0')}-0000-4000-8000-000000000000",
            key = ByteArray(32) { 1 },
            nonce = ByteArray(16) { 2 },
            beneficiaryUuids = List(beneficiaryCount) { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" },
        )
}

/** The cap on an expense's beneficiaries, which the library holds, see [ExpenseLimits.MAX_BENEFICIARIES]. */
private const val MAX_BENEFICIARIES = 100
