package expense

import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.PendingExpenseKeyEntity
import database.data.local.entity.PendingExpenseKeyState
import database.data.remote.MessageLimitScope
import kotlinx.coroutines.runBlocking
import message.DirectSendError
import message.ResendsBooked
import util.EmptyResult
import util.NetworkError
import util.Result
import util.nowEpochMilliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val USER = "22222222-2222-2222-2222-222222222222"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "77777777-7777-7777-7777-777777777777"

private const val LUNCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val DINNER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val TAXI = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val CINEMA = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

private const val MINUTE = 60L * 1000
private const val HOUR = 60 * MINUTE

/**
 * The user asking the creditors of the one-time expenses this device cannot open for their keys,
 * against the server's two limits on messages and each creditor's budget of requests. Every
 * creditor is a contact, so every key due from them is asked for.
 */
class ExpenseKeyPullTest {

    private val pending = PendingKeys()
    private val requests = KeyRequestsSent()
    private val resends = ResendsBooked()

    private val pull = ExpenseKeyPull(
        pendingExpenseKeyDao = pending,
        expectedFrom = { ExpectedKeys(isContact = true, awaitedAtRemoval = emptySet()) },
        send = requests::send,
        resendScheduler = resends,
    )

    /** One request per creditor, naming every key due from them, which is then a try. */
    @Test
    fun `a creditor is asked for every key due from them at once`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        pending.add(DINNER, creditorUuid = ALICE)

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(listOf(ALICE), requests.recipients)
        assertEquals(
            listOf(LUNCH, DINNER),
            assertNotNull(ExpenseKeyRequest.parse(plaintext = requests.payloads.single())).expenseUuids,
        )
        assertEquals(1, pending.rows.getValue(LUNCH).requestCount)
        assertNotNull(pending.budgets[ALICE])
        assertTrue(resends.seconds.isEmpty())
    }

    /**
     * What the user may send Alice is spent, which costs nothing of what they may send the
     * others: hers stay due, and are no try, so the pass still asks three others.
     */
    @Test
    fun `a creditor past their own limit stays due and the next one is asked in their place`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        pending.add(DINNER, creditorUuid = BOB)
        pending.add(TAXI, creditorUuid = CAROL)
        pending.add(CINEMA, creditorUuid = DAVE)
        requests.outcomes[ALICE] =
            Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 20, scope = MessageLimitScope.RECIPIENT))

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(listOf(ALICE, BOB, CAROL, DAVE), requests.recipients)
        assertNull(pending.rows.getValue(LUNCH).lastRequestedAt)
        assertEquals(0, pending.rows.getValue(LUNCH).requestCount)
        assertTrue(pending.budgets.getValue(ALICE) <= nowEpochMilliseconds())
        assertNotNull(pending.rows.getValue(CINEMA).lastRequestedAt)
        assertEquals(listOf(20L), resends.seconds)
    }

    /**
     * Sending more only pushes the count further past the limit: the rest wait, still due, for
     * the limit to reopen.
     */
    @Test
    fun `past the user's own limit the pass stops and is booked again for when it reopens`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        pending.add(DINNER, creditorUuid = BOB)
        requests.outcomes[ALICE] =
            Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER))

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(listOf(ALICE), requests.recipients)
        assertNull(pending.rows.getValue(LUNCH).lastRequestedAt)
        assertNull(pending.rows.getValue(DINNER).lastRequestedAt)
        assertEquals(listOf(45L), resends.seconds)
    }

    /**
     * Alice was asked for lunch a few minutes ago, which is one request of hers outstanding.
     * Asked for dinner as the user runs past their limit, she is asked again by the resend the
     * refusal booked: the refused request gave back the second request it had spent, which would
     * otherwise have held the resend back for the hour.
     */
    @Test
    fun `a request refused for a limit gives the budget back to the resend it books`() = runBlocking<Unit> {
        val outstanding = nowEpochMilliseconds() + 55 * MINUTE
        pending.add(DINNER, creditorUuid = ALICE)
        pending.budgets[ALICE] = outstanding
        requests.outcomes[ALICE] =
            Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER))

        pull.requestMissingKeys(ownUserUuid = USER)

        assertEquals(outstanding, pending.budgets[ALICE])

        requests.outcomes.clear()

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(listOf(ALICE, ALICE), requests.recipients)
        assertEquals(1, pending.rows.getValue(DINNER).requestCount)
    }

    /** Two requests at once and one more an hour after that, whatever the backoff allows. */
    @Test
    fun `a creditor whose budget is spent is left due`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        pending.budgets[ALICE] = nowEpochMilliseconds() + 2 * HOUR

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertTrue(requests.recipients.isEmpty())
        assertNull(pending.rows.getValue(LUNCH).lastRequestedAt)
    }

    /**
     * Alice answered a request for a hundred keys with the two shares her budget of replies
     * covered, see `replyInShares`, and the sync dropped the expenses they opened. The rest were
     * tried together, so they are due again together, and the next request names every one.
     */
    @Test
    fun `a request answered in part is asked again for every key still missing`() = runBlocking<Unit> {
        val expenseUuids = List(ExpenseKeyRequest.MAX_EXPENSES) {
            "${it.toString().padStart(8, '0')}-0000-4000-8000-000000000000"
        }

        expenseUuids.forEach { pending.add(it, creditorUuid = ALICE) }

        pull.requestMissingKeys(ownUserUuid = USER)

        assertEquals(expenseUuids, assertNotNull(ExpenseKeyRequest.parse(plaintext = requests.payloads.last())).expenseUuids)

        val missing = expenseUuids.drop(48)

        pending.rows.keys.retainAll(missing.toSet())
        pending.requestedAgo(millis = 2 * HOUR)
        pending.budgets.clear()

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(listOf(ALICE, ALICE), requests.recipients)
        assertEquals(missing, assertNotNull(ExpenseKeyRequest.parse(plaintext = requests.payloads.last())).expenseUuids)
    }

    /**
     * Nobody holds Alice's keys any more, the ones this request named and every other: none is
     * asked for again, and that is no failure.
     */
    @Test
    fun `a creditor who deleted their account is never asked again`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        pending.add(DINNER, creditorUuid = ALICE)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.RecipientDeleted)

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(PendingExpenseKeyState.CREDITOR_GONE, pending.rows.getValue(LUNCH).state)
        assertEquals(PendingExpenseKeyState.CREDITOR_GONE, pending.rows.getValue(DINNER).state)

        pending.budgets.clear()
        pull.requestMissingKeys(ownUserUuid = USER)

        assertEquals(listOf(ALICE), requests.recipients)
    }

    /**
     * The proxy answers a `404` while the API is being redeployed, so it says nothing of Alice's
     * account: her keys stay due, as after any other failure, and the request is charged to her
     * budget, the server having perhaps taken it. An account an administrator removed answers one
     * for good, which no retry of the sync changes, so it is not reported.
     */
    @Test
    fun `a 404 leaves the creditor's keys due and is not reported`() = runBlocking<Unit> {
        pending.add(LUNCH, creditorUuid = ALICE)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.Network(NetworkError.NOT_FOUND))

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys(ownUserUuid = USER))

        assertEquals(PendingExpenseKeyState.PENDING, pending.rows.getValue(LUNCH).state)
        assertTrue(pending.budgets.getValue(ALICE) > nowEpochMilliseconds())
    }
}

/** Sends nothing anywhere, answering each recipient as told and every other one with success. */
private class KeyRequestsSent {
    val outcomes = mutableMapOf<String, EmptyResult<DirectSendError>>()

    val recipients = mutableListOf<String>()
    val payloads = mutableListOf<ByteArray>()

    suspend fun send(recipientUserUuid: String, payload: ByteArray): EmptyResult<DirectSendError> {
        recipients += recipientUserUuid
        payloads += payload

        return outcomes[recipientUserUuid] ?: Result.Success(Unit)
    }
}

/** The expenses waiting for their keys, by expense, and each creditor's budget of requests. */
private class PendingKeys : PendingExpenseKeyDao {
    val rows = mutableMapOf<String, PendingExpenseKeyEntity>()
    val budgets = mutableMapOf<String, Long>()

    /** Seen in the order added, which is the order the pass asks in. */
    fun add(expenseUuid: String, creditorUuid: String) {
        rows[expenseUuid] = PendingExpenseKeyEntity(
            expenseUuid = expenseUuid,
            creditorUuid = creditorUuid,
            firstSeenAt = rows.size.toLong(),
            lastRequestedAt = null,
        )
    }

    /** Has every key asked for been last asked for [millis] ago. */
    fun requestedAgo(millis: Long) {
        rows.values.filter { it.lastRequestedAt != null }.forEach { row ->
            rows[row.expenseUuid] = PendingExpenseKeyEntity(
                expenseUuid = row.expenseUuid,
                creditorUuid = row.creditorUuid,
                firstSeenAt = row.firstSeenAt,
                lastRequestedAt = nowEpochMilliseconds() - millis,
                requestCount = row.requestCount,
                state = row.state,
                isAwaitedAtRemoval = row.isAwaitedAtRemoval,
            )
        }
    }

    override suspend fun getInState(state: PendingExpenseKeyState): List<PendingExpenseKeyEntity> =
        rows.values.filter { it.state == state }.sortedWith(compareBy({ it.firstSeenAt }, { it.expenseUuid }))

    override suspend fun markRequested(expenseUuids: List<String>, requestedAt: Long) {
        expenseUuids.forEach { expenseUuid ->
            val row = rows.getValue(expenseUuid)

            rows[expenseUuid] = PendingExpenseKeyEntity(
                expenseUuid = row.expenseUuid,
                creditorUuid = row.creditorUuid,
                firstSeenAt = row.firstSeenAt,
                lastRequestedAt = requestedAt,
                requestCount = row.requestCount + 1,
                state = row.state,
                isAwaitedAtRemoval = row.isAwaitedAtRemoval,
            )
        }
    }

    override suspend fun setState(expenseUuids: List<String>, state: PendingExpenseKeyState) {
        expenseUuids.forEach { expenseUuid ->
            val row = rows.getValue(expenseUuid)

            rows[expenseUuid] = PendingExpenseKeyEntity(
                expenseUuid = row.expenseUuid,
                creditorUuid = row.creditorUuid,
                firstSeenAt = row.firstSeenAt,
                lastRequestedAt = row.lastRequestedAt,
                requestCount = row.requestCount,
                state = state,
                isAwaitedAtRemoval = row.isAwaitedAtRemoval,
            )
        }
    }

    override suspend fun getRequestBudgetFullAt(creditorUuid: String): Long? = budgets[creditorUuid]

    override suspend fun setRequestBudget(budget: KeyRequestBudgetEntity) {
        budgets[budget.creditorUuid] = budget.budgetFullAt
    }

    override suspend fun insertNew(expenseUuid: String, creditorUuid: String, firstSeenAt: Long) = error("Not used here")
    override suspend fun markAwaitedAtRemoval(creditorUuid: String) = error("Not used here")
    override suspend fun getAwaitedAtRemoval(creditorUuid: String): List<String> = error("Not used here")
    override suspend fun deleteNotIn(expenseUuids: List<String>) = error("Not used here")
    override suspend fun deleteAll() = error("Not used here")
}
