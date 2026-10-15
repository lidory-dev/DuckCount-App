package push

import kotlin.test.Test
import kotlin.test.assertEquals

private const val ALICE = "11111111-1111-1111-1111-111111111111"

class PushSignalTest {

    @Test
    fun `parses a contact request signal`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.CONTACT_REQUEST, from = ALICE),
            PushSignal.parse("""{"type":"contact_request","from":"$ALICE"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `parses a contact accepted signal`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.CONTACT_ACCEPTED, from = ALICE),
            PushSignal.parse("""{"type":"contact_accepted","from":"$ALICE"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `parses a message signal`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.MESSAGE, from = ALICE),
            PushSignal.parse("""{"type":"message","from":"$ALICE"}""".encodeToByteArray()),
        )
    }

    /**
     * The wire name the server sends, which a locked device leans on entirely: told only
     * `message` it shows nothing, since the same signal covers a contact removing the user.
     */
    @Test
    fun `parses a one-time expense signal`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.ONE_TIME_EXPENSE, from = ALICE),
            PushSignal.parse("""{"type":"one_time_expense","from":"$ALICE"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `parses a one-time expense changed signal`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.ONE_TIME_EXPENSE_CHANGED, from = ALICE),
            PushSignal.parse(
                """{"type":"one_time_expense_changed","from":"$ALICE"}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `reads a signal a newer server added as unknown`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.UNKNOWN, from = ALICE),
            PushSignal.parse("""{"type":"group_message","from":"$ALICE"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `ignores fields a newer server added`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.MESSAGE, from = ALICE),
            PushSignal.parse(
                """{"type":"message","from":"$ALICE","sent_at":"2026-09-18"}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `reads a signal naming no sender as from nobody`() {
        assertEquals(
            PushSignal(kind = PushSignal.Kind.MESSAGE, from = null),
            PushSignal.parse("""{"type":"message"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `reads a payload it cannot parse as unknown and from nobody`() {
        val unreadable = PushSignal(kind = PushSignal.Kind.UNKNOWN, from = null)

        assertEquals(unreadable, PushSignal.parse("not json".encodeToByteArray()))
        assertEquals(unreadable, PushSignal.parse("{}".encodeToByteArray()))
        assertEquals(unreadable, PushSignal.parse(ByteArray(0)))
    }
}
