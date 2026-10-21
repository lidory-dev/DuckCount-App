package expense

import database.data.local.dao.OwedExpenseKeyDao
import database.data.local.entity.OwedExpenseKeyEntity
import database.data.remote.MessageLimitScope
import kotlinx.coroutines.runBlocking
import message.DirectSendError
import message.ResendsBooked
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val USER = "22222222-2222-2222-2222-222222222222"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"

private const val LUNCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val DINNER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * The keys to the user's own expenses going out to whoever is owed them, against the server's
 * two limits on messages. Alice, Bob and Carol are on both of the user's expenses.
 */
class ExpenseKeyDeliveryTest {

    private val owed = OwedKeys()
    private val sends = KeySends()
    private val resends = ResendsBooked()

    private val delivery = ExpenseKeyDelivery(
        owedExpenseKeyDao = owed,
        record = { expenseUuid -> recordOf(expenseUuid) },
        send = sends::send,
        resendScheduler = resends,
    )

    @Test
    fun `everybody owed a key is handed it`() = runBlocking<Unit> {
        owed.add(LUNCH, ALICE, BOB, CAROL)

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(ALICE, BOB, CAROL), sends.recipients)
        assertTrue(owed.rows.isEmpty())
        assertTrue(resends.seconds.isEmpty())
    }

    /**
     * Past what the user may send Alice, which costs nothing of what they may send the others:
     * hers waits for the limit to reopen, and Bob and Carol are handed theirs meanwhile.
     */
    @Test
    fun `a recipient past their own limit waits while the others are handed their keys`() = runBlocking<Unit> {
        owed.add(LUNCH, ALICE, BOB, CAROL)
        sends.outcomes[ALICE] = Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 20, scope = MessageLimitScope.RECIPIENT))

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(ALICE, BOB, CAROL), sends.recipients)
        assertEquals(listOf(LUNCH to ALICE), owed.rows)
        assertEquals(listOf(20L), resends.seconds)
    }

    /** Sending more only pushes the count further past the limit: the rest wait for it to reopen. */
    @Test
    fun `past the user's own limit the pass stops and is booked again for when it reopens`() = runBlocking<Unit> {
        owed.add(LUNCH, ALICE, BOB, CAROL)
        sends.outcomes[BOB] = Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER))

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(ALICE, BOB), sends.recipients)
        assertEquals(listOf(LUNCH to BOB, LUNCH to CAROL), owed.rows)
        assertEquals(listOf(45L), resends.seconds)
    }

    /**
     * Refused for the first expense, Bob is owed the second's key too by the time the resend
     * runs: one message hands him both.
     */
    @Test
    fun `keys left waiting go out together at the next pass`() = runBlocking<Unit> {
        owed.add(LUNCH, BOB)
        sends.outcomes[BOB] = Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER))

        delivery.handOver(ownUserUuid = USER)

        owed.add(DINNER, BOB)
        sends.outcomes.clear()

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(BOB, BOB), sends.recipients)

        val share = assertNotNull(ExpenseKeyShare.parse(plaintext = sends.payloads.last()))

        assertEquals(listOf(LUNCH, DINNER), share.keys.map { it.expenseUuid })
        assertTrue(owed.rows.isEmpty())
    }

    /**
     * Bob is owed more keys than one message carries, as when his identity was in doubt for
     * as many expenses: a pass hands him one message's worth, and the rest wait for the next.
     */
    @Test
    fun `past what one message carries the rest wait for the pass after`() = runBlocking<Unit> {
        val expenseUuids = List(ExpenseKeyShare.MAX_KEYS + 50) {
            "${it.toString().padStart(8, '0')}-0000-4000-8000-000000000000"
        }

        expenseUuids.forEach { owed.add(it, BOB) }

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(BOB), sends.recipients)
        assertEquals(
            expenseUuids.take(ExpenseKeyShare.MAX_KEYS),
            assertNotNull(ExpenseKeyShare.parse(plaintext = sends.payloads.last())).keys.map { it.expenseUuid },
        )
        assertEquals(expenseUuids.drop(ExpenseKeyShare.MAX_KEYS).map { it to BOB }, owed.rows)

        assertIs<Result.Success<Unit>>(delivery.handOver(ownUserUuid = USER))

        assertEquals(listOf(BOB, BOB), sends.recipients)
        assertEquals(
            expenseUuids.drop(ExpenseKeyShare.MAX_KEYS),
            assertNotNull(ExpenseKeyShare.parse(plaintext = sends.payloads.last())).keys.map { it.expenseUuid },
        )
        assertTrue(owed.rows.isEmpty())
        assertTrue(resends.seconds.isEmpty())
    }

    /** A failure met before the limit ran out is still the pass's to report. */
    @Test
    fun `a failure before the limit ran out is reported`() = runBlocking<Unit> {
        owed.add(LUNCH, ALICE, BOB, CAROL)
        sends.outcomes[ALICE] = Result.Error(DirectSendError.Network(NetworkError.REQUEST_TIMEOUT))
        sends.outcomes[BOB] = Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 45, scope = MessageLimitScope.SENDER))

        val handOver = delivery.handOver(ownUserUuid = USER)

        assertEquals(
            OneTimeExpenseSyncError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<OneTimeExpenseSyncError>>(handOver).error,
        )
        assertEquals(listOf(ALICE, BOB), sends.recipients)
        assertEquals(listOf(45L), resends.seconds)
    }

    /** The user's own record of an expense they created, naming everybody on it. */
    private fun recordOf(expenseUuid: String) =
        OneTimeExpenseKeyRecord(
            expenseUuid = expenseUuid,
            key = ByteArray(32) { 1 },
            creditorUuid = USER,
            nonce = ByteArray(16) { 2 },
            creditorSignatureKey = ByteArray(32) { 3 },
            beneficiaryUuids = listOf(ALICE, BOB, CAROL, USER).sorted(),
        )
}

/** Sends nothing anywhere, answering each recipient as told and every other one with success. */
private class KeySends {
    val outcomes = mutableMapOf<String, EmptyResult<DirectSendError>>()

    val recipients = mutableListOf<String>()
    val payloads = mutableListOf<ByteArray>()

    suspend fun send(recipientUserUuid: String, payload: ByteArray): EmptyResult<DirectSendError> {
        recipients += recipientUserUuid
        payloads += payload

        return outcomes[recipientUserUuid] ?: Result.Success(Unit)
    }
}

/**
 * The keys owed, as expense and recipient, every one on an expense this device shows naming
 * its recipient, which is what the query the pass reads them with keeps.
 */
private class OwedKeys : OwedExpenseKeyDao {
    private val owed = mutableSetOf<Pair<String, String>>()

    /** What is still owed, in the order the query reads it: by recipient, then by expense. */
    val rows: List<Pair<String, String>> get() = owed.sortedWith(compareBy({ it.second }, { it.first }))

    fun add(expenseUuid: String, vararg recipientUuids: String) {
        recipientUuids.forEach { owed += expenseUuid to it }
    }

    override suspend fun getListed(): List<OwedExpenseKeyEntity> =
        rows.map { (expenseUuid, recipientUuid) -> OwedExpenseKeyEntity(expenseUuid = expenseUuid, recipientUuid = recipientUuid) }

    override suspend fun delete(recipientUuid: String, expenseUuids: List<String>) {
        owed.removeAll { (expenseUuid, recipient) -> recipient == recipientUuid && expenseUuid in expenseUuids }
    }

    override suspend fun insertAll(keys: List<OwedExpenseKeyEntity>) = error("Not used here")
    override suspend fun deleteNotIn(expenseUuid: String, recipientUuids: List<String>) = error("Not used here")
    override suspend fun deleteFor(expenseUuid: String) = error("Not used here")
}
