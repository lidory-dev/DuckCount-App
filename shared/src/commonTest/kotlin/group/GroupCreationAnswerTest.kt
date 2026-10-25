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

private const val ALICE = "3cf93fad-a390-4104-a046-679a62e7ce04"
private const val BOB = "a25df1b3-4124-4164-a2db-1d37da9e8d23"

/**
 * What a `POST /groups` is read as: off the response itself, see [creationRefusalOf], or else from
 * what it failed with, see [creationAnswerOf].
 */
class GroupCreationAnswerTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a 410 names every invitee it says has no account`() = runBlocking<Unit> {
        val refusal = creationRefusalOf(status = HttpStatusCode.Gone, headers = headersOf()) {
            json.decodeFromString("""{"deleted_user_uuids":["$ALICE","$BOB"]}""")
        }

        assertEquals(GroupCreationAnswer.DeletedInvitees(userUuids = listOf(ALICE, BOB)), refusal)
    }

    @Test
    fun `a 429 is sent again once its Retry-After is up`() = runBlocking<Unit> {
        val refusal = creationRefusalOf(
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "42"),
        ) { fail("Only a 410 has a body to read") }

        assertEquals(GroupCreationAnswer.RateLimited(retryAfterSeconds = 42), refusal)
    }

    /** A minute, the shortest window the server has. */
    @Test
    fun `a 429 whose Retry-After does not read is sent again after a minute`() = runBlocking<Unit> {
        val refusal = creationRefusalOf(status = HttpStatusCode.TooManyRequests, headers = headersOf()) {
            fail("Only a 410 has a body to read")
        }

        assertEquals(GroupCreationAnswer.RateLimited(retryAfterSeconds = 60), refusal)
    }

    @Test
    fun `any other answer leaves the response unread`() = runBlocking<Unit> {
        listOf(HttpStatusCode.Conflict, HttpStatusCode.BadRequest, HttpStatusCode.Created).forEach { status ->
            assertNull(creationRefusalOf(status = status, headers = headersOf()) { fail("Only a 410 has a body to read") })
        }
    }

    /** An earlier send of the same creation, whose answer was lost: every group gets a nonce of its own. */
    @Test
    fun `a 409 is a group the server already holds`() {
        assertEquals(GroupCreationAnswer.AlreadyExists, creationAnswerOf(error = NetworkError.CONFLICT))
    }

    @Test
    fun `a body the server refuses is refused for good`() {
        listOf(NetworkError.BAD_REQUEST, NetworkError.PAYLOAD_TOO_LARGE, NetworkError.UNPROCESSABLE_ENTITY).forEach {
            assertEquals(GroupCreationAnswer.Refused(error = it), creationAnswerOf(error = it))
        }
    }

    /**
     * The proxy in front of the API answers a `404` for every route while the API is being
     * redeployed, and a `401` may still be turned around by a refresh: neither says the group was
     * not stored for good, so neither has it deleted.
     */
    @Test
    fun `an answer that says nothing of the group is sent again`() {
        listOf(
            NetworkError.UNAUTHORIZED,
            NetworkError.NOT_FOUND,
            NetworkError.REQUEST_TIMEOUT,
            NetworkError.SERVER_ERROR,
            NetworkError.NO_INTERNET,
            NetworkError.UNREACHABLE,
            NetworkError.UNKNOWN,
        ).forEach {
            assertEquals(GroupCreationAnswer.Failed(error = it), creationAnswerOf(error = it))
        }
    }
}
