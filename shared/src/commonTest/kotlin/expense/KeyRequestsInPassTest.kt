package expense

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "44444444-4444-4444-4444-444444444444"
private const val EXPENSE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val EXPENSE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

class KeyRequestsInPassTest {

    private val lostGroup = ByteArray(16) { 1 }
    private val currentGroup = ByteArray(16) { 2 }

    /** Asked again on the asker's backoff while this device was locked. */
    @Test
    fun `copies of one request are answered once`() {
        val requests = KeyRequestsInPass()

        repeat(3) {
            requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_A))
        }

        val asked = requests.drain().single()

        assertEquals(BOB, asked.requesterUuid)
        assertEquals(listOf(EXPENSE_A), asked.expenseUuids)
    }

    /**
     * The asker asked twice from a session they have since logged out of, then again from
     * the group they built on logging back in. Answered in the old group, the keys would be
     * spent on messages nobody can read.
     */
    @Test
    fun `an asker back from a log-out is answered in the group they asked from last`() {
        val requests = KeyRequestsInPass()

        requests.add(requesterUuid = BOB, mlsGroupId = lostGroup, expenseUuids = listOf(EXPENSE_A))
        requests.add(requesterUuid = BOB, mlsGroupId = lostGroup, expenseUuids = listOf(EXPENSE_A))
        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_A))

        assertContentEquals(currentGroup, requests.drain().single().mlsGroupId)
    }

    /** One of the asker's rows can be waiting out a longer backoff than the rest. */
    @Test
    fun `what an earlier request named is answered too and comes after the latest`() {
        val requests = KeyRequestsInPass()

        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_A))
        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_B))

        assertEquals(listOf(EXPENSE_B, EXPENSE_A), requests.drain().single().expenseUuids)
    }

    @Test
    fun `an answer holds no more than one share carries and keeps the latest first`() {
        val requests = KeyRequestsInPass()
        val earlier = List(ExpenseKeyShare.MAX_KEYS) { "earlier-$it" }
        val latest = List(ExpenseKeyShare.MAX_KEYS) { "latest-$it" }

        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = earlier)
        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = latest)

        assertEquals(latest, requests.drain().single().expenseUuids)
    }

    @Test
    fun `each asker gets an answer of their own`() {
        val requests = KeyRequestsInPass()

        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_A))
        requests.add(requesterUuid = CAROL, mlsGroupId = lostGroup, expenseUuids = listOf(EXPENSE_B))

        val asked = requests.drain().associateBy { it.requesterUuid }

        assertEquals(listOf(EXPENSE_A), asked.getValue(BOB).expenseUuids)
        assertEquals(listOf(EXPENSE_B), asked.getValue(CAROL).expenseUuids)
    }

    @Test
    fun `a pass leaves nothing for the next one`() {
        val requests = KeyRequestsInPass()

        requests.add(requesterUuid = BOB, mlsGroupId = currentGroup, expenseUuids = listOf(EXPENSE_A))
        requests.drain()

        assertTrue(requests.drain().isEmpty())
    }
}
