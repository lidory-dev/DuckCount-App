package group

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import util.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val DAVE = "44444444-4444-4444-4444-444444444444"

/**
 * What a Commit sent to a group is read as: off the response itself, see [membershipRefusalOf], or
 * else from what it failed with, see [membershipAnswerOf].
 */
class GroupMembershipAnswerTest {

    @Test
    fun `a 410 names every new member it says has no account`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.Gone, headers = headersOf()) {
            """{"deleted_user_uuids":["$DAVE"]}"""
        }

        assertEquals(GroupMembershipAnswer.DeletedMembers(userUuids = listOf(DAVE)), refusal)
    }

    /** Another member's Commit got there first: the server stores one per epoch. */
    @Test
    fun `a JSON 409 is a Commit for an epoch the group left`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) {
            """{"key_generation":7}"""
        }

        assertEquals(GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 7), refusal)
    }

    /** An earlier send of the same change, whose answer was lost: every change gets a nonce of its own. */
    @Test
    fun `a plain 409 is a change the server already made`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) {
            "A change to this group already exists for this nonce."
        }

        assertEquals(GroupMembershipAnswer.AlreadyMade, refusal)
    }

    /** A replacement the server finds no Commit to replace for: sent again, it hears the same. */
    @Test
    fun `a 409 with nothing to replace is refused for good`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) {
            "Nothing to replace."
        }

        assertEquals(GroupMembershipAnswer.Refused(error = NetworkError.CONFLICT), refusal)
    }

    @Test
    fun `a 429 is sent again once its Retry-After is up`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "42"),
        ) { fail("Only a 409 and a 410 have a body to read") }

        assertEquals(GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42), refusal)
    }

    /** A minute, the shortest window the server has. */
    @Test
    fun `a 429 whose Retry-After does not read is sent again after a minute`() = runBlocking<Unit> {
        val refusal = membershipRefusalOf(status = HttpStatusCode.TooManyRequests, headers = headersOf()) {
            fail("Only a 409 and a 410 have a body to read")
        }

        assertEquals(GroupMembershipAnswer.RateLimited(retryAfterSeconds = 60), refusal)
    }

    @Test
    fun `any other answer leaves the response unread`() = runBlocking<Unit> {
        listOf(HttpStatusCode.OK, HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
            .forEach { status ->
                val refusal = membershipRefusalOf(status = status, headers = headersOf()) {
                    fail("Only a 409 and a 410 have a body to read")
                }

                assertNull(refusal)
            }
    }

    /** Read off the response, see above: what a 409 whose body could not be read comes to. */
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
            NetworkError.FORBIDDEN,
            NetworkError.UNKNOWN,
        ).forEach {
            assertEquals(GroupMembershipAnswer.Failed(error = it), membershipAnswerOf(error = it))
        }
    }
}
