package database.data.remote

import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a response from `POST /messages` says beyond what the client reads it as, see [refusalOf]. */
class SendDirectMessageRefusalTest {

    /** What the server answers once the recipient's account is gone, from the trace it leaves. */
    @Test
    fun `a 410 is a recipient who deleted their account`() {
        assertEquals(SendDirectMessageError.RecipientDeleted, refusalOf(status = HttpStatusCode.Gone, headers = headersOf()))
    }

    /**
     * The proxy in front of the API answers a `404` for every route while the API is being
     * redeployed, so it says no more than the client reads it as, and nobody is dropped on it.
     */
    @Test
    fun `a 404 says nothing of the recipient`() {
        assertNull(refusalOf(status = HttpStatusCode.NotFound, headers = headersOf()))
    }

    @Test
    fun `a 429 names the limit that refused the message`() {
        val headers = headersOf(
            "Retry-After" to listOf("17"),
            "X-Rate-Limit-Scope" to listOf("recipient"),
        )

        assertEquals(
            SendDirectMessageError.RateLimited(retryAfterSeconds = 17, scope = MessageLimitScope.RECIPIENT),
            refusalOf(status = HttpStatusCode.TooManyRequests, headers = headers),
        )
    }

    @Test
    fun `a message taken says nothing more`() {
        assertNull(refusalOf(status = HttpStatusCode.OK, headers = headersOf()))
    }
}
