package push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

/**
 * Who may wake the user: Bob is a contact, and Alice has a contact request from the user she
 * has yet to answer. Mallory is a stranger, or a former contact, which comes to the same.
 */
class WakeDecisionTest {

    private val wakeList = WakeListOf(contacts = setOf(BOB), requested = setOf(ALICE))

    @Test
    fun `a contact request wakes the user whoever sent it`() {
        assertTrue(signal(PushSignal.Kind.CONTACT_REQUEST, from = MALLORY).mayWake(wakeList))
        assertTrue(signal(PushSignal.Kind.CONTACT_REQUEST, from = BOB).mayWake(wakeList))
        assertTrue(signal(PushSignal.Kind.CONTACT_REQUEST, from = null).mayWake(wakeList))
    }

    @Test
    fun `every signal from a contact wakes the user`() {
        PushSignal.Kind.entries.forEach { kind ->
            assertTrue(signal(kind, from = BOB).mayWake(wakeList), "$kind")
        }
    }

    @Test
    fun `nothing but a contact request from a stranger wakes the user`() {
        (PushSignal.Kind.entries - PushSignal.Kind.CONTACT_REQUEST).forEach { kind ->
            assertFalse(signal(kind, from = MALLORY).mayWake(wakeList), "$kind")
        }
    }

    /** Her acceptance, which makes her a contact only once the sync has read it. */
    @Test
    fun `a message from somebody the user sent a request to wakes the user`() {
        assertTrue(signal(PushSignal.Kind.MESSAGE, from = ALICE).mayWake(wakeList))
    }

    /** Only her answer is waited for: anything else of hers is from somebody not a contact. */
    @Test
    fun `nothing else from somebody the user sent a request to wakes the user`() {
        listOf(
            PushSignal.Kind.ONE_TIME_EXPENSE,
            PushSignal.Kind.ONE_TIME_EXPENSE_CHANGED,
            PushSignal.Kind.UNKNOWN,
        ).forEach { kind ->
            assertFalse(signal(kind, from = ALICE).mayWake(wakeList), "$kind")
        }
    }

    /** A payload with no sender a build can read, or none at all. */
    @Test
    fun `a signal naming nobody wakes the user only as a contact request`() {
        (PushSignal.Kind.entries - PushSignal.Kind.CONTACT_REQUEST).forEach { kind ->
            assertFalse(signal(kind, from = null).mayWake(wakeList), "$kind")
        }
    }

    private fun signal(kind: PushSignal.Kind, from: String?) = PushSignal(kind = kind, from = from)
}

/** A list as the platform would keep it, its UUIDs in the clear. */
private class WakeListOf(
    private val contacts: Set<String>,
    private val requested: Set<String>,
) : WakeListStore {
    override fun isContact(userUuid: String): Boolean = userUuid in contacts

    override fun isRequested(userUuid: String): Boolean = userUuid in requested

    override suspend fun replace(contacts: Set<String>, requested: Set<String>) {
        error("Only read here")
    }

    override suspend fun clear() {
        error("Only read here")
    }
}
