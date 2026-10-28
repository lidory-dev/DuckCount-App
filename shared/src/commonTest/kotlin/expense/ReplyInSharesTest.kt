package expense

import channel.Reply
import channel.spendReply
import kotlinx.coroutines.runBlocking
import message.DirectSendError
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MINUTE = 60L * 1000
private const val HOUR = 60 * MINUTE

/** Some moment, far enough from zero that nothing below can go negative. */
private const val T = 1_800_000_000_000L

/**
 * The creditor answering a request for the hundred keys one can ask for, to expenses of a hundred
 * beneficiaries each, five shares, out of the asker's budget of replies, see [replyInShares] and
 * [spendReply].
 */
class ReplyInSharesTest {

    private val keys = List(ExpenseKeyShare.MAX_KEYS) { index ->
        ExpenseKey(
            expenseUuid = "${index.toString().padStart(8, '0')}-0000-4000-8000-000000000000",
            key = ByteArray(32) { 1 },
            nonce = ByteArray(16) { 2 },
            beneficiaryUuids = List(100) { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" },
        )
    }

    /** When the asker's budget is whole again, as the channel records it, and the time it is now. */
    private var budgetFullAt: Long? = null
    private var now = T

    private var claims = 0
    private val replies = mutableListOf<ByteArray>()

    /** What the server answers each reply, in turn, and success past them. */
    private val answers = mutableListOf<EmptyResult<DirectSendError>>()

    @Test
    fun `each share draws one reply and those the budget does not cover are not sent`() = runBlocking<Unit> {
        val handedOver = answer()

        assertEquals(2, replies.size)
        assertEquals(3, claims)
        assertEquals(sharedKeys(), handedOver)
        assertEquals(keys.take(handedOver.size).map { it.expenseUuid }, handedOver)
    }

    /** The asker asks again for what is still missing, and is answered as far as the budget lets. */
    @Test
    fun `the rest goes out as the budget fills again`() = runBlocking<Unit> {
        val first = answer()

        now = T + HOUR

        val second = answer(keys = keys.drop(first.size))

        now = T + 3 * HOUR

        val third = answer(keys = keys.drop(first.size + second.size))

        assertEquals(listOf(2, 1, 2), listOf(first, second, third).map { handed -> sharesOf(handed) })
        assertEquals(keys.map { it.expenseUuid }, first + second + third)
    }

    /**
     * The reply is kept when its send fails, see `DirectChannelRepository.claimReply`: one failing
     * stops the shares after it rather than spend a reply on each.
     */
    @Test
    fun `a share that does not go out stops the ones after it`() = runBlocking<Unit> {
        answers += Result.Error(DirectSendError.Network(NetworkError.REQUEST_TIMEOUT))

        val handedOver = answer()

        assertTrue(handedOver.isEmpty())
        assertEquals(1, replies.size)
        assertEquals(1, claims)
    }

    @Test
    fun `an answer within one share is one reply`() = runBlocking<Unit> {
        val handedOver = answer(keys = keys.take(3))

        assertEquals(keys.take(3).map { it.expenseUuid }, handedOver)
        assertEquals(1, replies.size)
        assertEquals(1, claims)
    }

    private suspend fun answer(keys: List<ExpenseKey> = this.keys): List<String> =
        replyInShares(
            keys = keys,
            claim = {
                claims++
                spendReply(budgetFullAt = budgetFullAt, now = now, reply = Reply.EXPENSE_KEYS)
                    ?.also { budgetFullAt = it } != null
            },
            reply = { payload ->
                replies += payload
                answers.removeFirstOrNull() ?: Result.Success(Unit)
            },
        )

    /** The expenses whose keys every reply sent so far handed over. */
    private fun sharedKeys(): List<String> =
        replies.flatMap { reply -> assertNotNull(ExpenseKeyShare.parse(plaintext = reply)).keys.map { it.expenseUuid } }

    /** How many shares the replies handing [handedOver] over took. */
    private fun sharesOf(handedOver: List<String>): Int =
        replies.count { reply ->
            assertNotNull(ExpenseKeyShare.parse(plaintext = reply)).keys.first().expenseUuid in handedOver
        }
}
