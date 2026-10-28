package group

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import util.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a `POST /groups/{group_uuid}/messages` is read as: off the response itself, see
 * [postRefusalOf], or else from what it failed with, see [postAnswerOf].
 */
class GroupPostAnswerTest {

    @Test
    fun `a 429 is posted again once its Retry-After is up`() {
        assertEquals(
            GroupPostAnswer.RateLimited(retryAfterSeconds = 42),
            postRefusalOf(status = HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "42")),
        )
    }

    /** A minute, the shortest window the server has. */
    @Test
    fun `a 429 whose Retry-After does not read is posted again after a minute`() {
        assertEquals(
            GroupPostAnswer.RateLimited(retryAfterSeconds = 60),
            postRefusalOf(status = HttpStatusCode.TooManyRequests, headers = headersOf()),
        )
    }

    @Test
    fun `any other answer leaves the response unread`() {
        listOf(HttpStatusCode.OK, HttpStatusCode.Conflict, HttpStatusCode.NotFound, HttpStatusCode.BadRequest).forEach {
            assertNull(postRefusalOf(status = it, headers = headersOf()))
        }
    }

    /** The post costs the allowance all the same, and a group with nobody else in it holds nobody's older key. */
    @Test
    fun `a 409 is a group with nobody else in it`() {
        assertEquals(GroupPostAnswer.NoOtherMembers, postAnswerOf(error = NetworkError.CONFLICT))
    }

    /** Removed from it, which the drain reads, or the proxy answering while the server is redeployed. */
    @Test
    fun `a 404 is a group the user is no current member of`() {
        assertEquals(GroupPostAnswer.NotMember, postAnswerOf(error = NetworkError.NOT_FOUND))
    }

    @Test
    fun `any other failure is posted again by the next pass`() {
        listOf(NetworkError.BAD_REQUEST, NetworkError.NO_INTERNET, NetworkError.SERVER_ERROR).forEach {
            assertEquals(GroupPostAnswer.Failed(error = it), postAnswerOf(error = it))
        }
    }
}
