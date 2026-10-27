package group

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import util.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val DAVE = "44444444-4444-4444-4444-444444444444"

/**
 * What a change to a group's members, or its key rotation, is read as: off the response itself,
 * see [membershipRefusalOf], or else from what it failed with, see [membershipAnswerOf].
 */
class GroupMembershipAnswerTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a 410 names every new member it says has no account`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.Gone, headers = headersOf()) {
            json.decodeFromString("""{"deleted_user_uuids":["$DAVE"]}""")
        }

        assertEquals(GroupMembershipAnswer.DeletedMembers(userUuids = listOf(DAVE)), refusal)
    }

    @Test
    fun `a 429 is sent again once its Retry-After is up`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "42"),
        ) { fail("Only a 410 has a body to read") }

        assertEquals(GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42), refusal)
    }

    /** A minute, the shortest window the server has. */
    @Test
    fun `a 429 whose Retry-After does not read is sent again after a minute`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.TooManyRequests, headers = headersOf()) {
            fail("Only a 410 has a body to read")
        }

        assertEquals(GroupMembershipAnswer.RateLimited(retryAfterSeconds = 60), refusal)
    }

    @Test
    fun `any other answer leaves the response unread`() = runBlocking<Unit> {
        listOf(HttpStatusCode.OK, HttpStatusCode.Conflict, HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
            .forEach { status ->
                val refusal = membershipRefusalOf(status = status, headers = headersOf()) {
                    fail("Only a 410 has a body to read")
                }

                assertNull(refusal)
            }
    }

    /** An earlier send of the same change, whose answer was lost: every change gets a nonce of its own. */
    @Test
    fun `a 409 is a change the server already made`() {
        assertEquals(GroupMembershipAnswer.AlreadyMade, membershipAnswerOf(error = NetworkError.CONFLICT))
    }

    @Test
    fun `a body the server refuses is refused for good`() {
        listOf(NetworkError.BAD_REQUEST, NetworkError.PAYLOAD_TOO_LARGE, NetworkError.UNPROCESSABLE_ENTITY).forEach {
            assertEquals(GroupMembershipAnswer.Refused(error = it), membershipAnswerOf(error = it))
        }
    }

    /**
     * The proxy in front of the API answers a `404` for every route while the API is being
     * redeployed, and a `401` may still be turned around by a refresh: neither says the change was
     * not stored, so neither has its Commit discarded, which for a change the server did store
     * would leave the creator in an epoch every member left.
     */
    @Test
    fun `an answer that says nothing of the change is sent again`() {
        listOf(
            NetworkError.UNAUTHORIZED,
            NetworkError.NOT_FOUND,
            NetworkError.REQUEST_TIMEOUT,
            NetworkError.SERVER_ERROR,
            NetworkError.NO_INTERNET,
            NetworkError.UNREACHABLE,
            NetworkError.SERIALIZATION,
            NetworkError.UNKNOWN,
        ).forEach {
            assertEquals(GroupMembershipAnswer.Failed(error = it), membershipAnswerOf(error = it))
        }
    }
}
