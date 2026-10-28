package database.data.remote

import networking.LARGE_BODY_TIMEOUT_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How long a send is given, see [sendTimeoutMillisOf]. */
class SendDirectMessageTimeoutTest {

    private fun bodyOf(length: Int) = SendDirectMessageBody(
        recipientUserUuid = "c7a1f0de-5b39-4d1e-9a64-0b2f8c1e7d55",
        ciphertext = "a".repeat(length),
    )

    /**
     * Most messages, a share of one key included, and often sent while the user waits: a
     * connection that stalls holds them for 5 s only.
     */
    @Test
    fun `a short message is given the time every request gets`() {
        assertNull(sendTimeoutMillisOf(body = bodyOf(length = 14_425)))
    }

    @Test
    fun `a message at the limit is still given the time every request gets`() {
        assertNull(sendTimeoutMillisOf(body = bodyOf(length = LARGE_DIRECT_MESSAGE_LENGTH)))
    }

    /** A share of the keys to many expenses, which a slow connection takes long to move. */
    @Test
    fun `a message past the limit is given the long timeout`() {
        assertEquals(LARGE_BODY_TIMEOUT_MILLIS, sendTimeoutMillisOf(body = bodyOf(length = LARGE_DIRECT_MESSAGE_LENGTH + 1)))
        assertEquals(LARGE_BODY_TIMEOUT_MILLIS, sendTimeoutMillisOf(body = bodyOf(length = 160_521)))
    }
}
