package push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushSignalTest {

    @Test
    fun `parses a contact request signal`() {
        assertEquals(
            PushSignal.CONTACT_REQUEST,
            PushSignal.parse("""{"type":"contact_request"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `parses a message signal`() {
        assertEquals(
            PushSignal.MESSAGE,
            PushSignal.parse("""{"type":"message"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `reads a signal a newer server added as unknown`() {
        assertEquals(
            PushSignal.UNKNOWN,
            PushSignal.parse("""{"type":"group_message"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `ignores fields a newer server added`() {
        assertEquals(
            PushSignal.MESSAGE,
            PushSignal.parse("""{"type":"message","sent_at":"2026-09-18"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `reads a payload it cannot parse as unknown`() {
        assertEquals(PushSignal.UNKNOWN, PushSignal.parse("not json".encodeToByteArray()))
        assertEquals(PushSignal.UNKNOWN, PushSignal.parse("{}".encodeToByteArray()))
        assertEquals(PushSignal.UNKNOWN, PushSignal.parse(ByteArray(0)))
    }
}
