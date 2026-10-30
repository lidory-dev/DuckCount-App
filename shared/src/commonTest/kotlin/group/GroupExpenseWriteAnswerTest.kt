package group

import database.data.remote.EncryptedFieldDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * What a write of a group expense is read as off the response itself, see [writeRefusalOf]: the
 * `409`s told apart by their bodies, as the API writes them, the `404`s by their text, and the
 * `429` by its `Retry-After`. And the bodies as the API reads them.
 */
class GroupExpenseWriteAnswerTest {

    @Test
    fun `a 409 naming a generation is a key the group replaced`() = runBlocking<Unit> {
        val refusal = writeRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) { """{"key_generation":3}""" }

        assertEquals(GroupExpenseWriteAnswer.KeyGenerationNotCurrent(keyGeneration = 3), refusal)
    }

    @Test
    fun `a 409 naming a revision is an update built on a version replaced`() = runBlocking<Unit> {
        val refusal = writeRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) { """{"revision":4}""" }

        assertEquals(GroupExpenseWriteAnswer.RevisionNotNext(revision = 4), refusal)
    }

    /** The plain text a create sent again hears, its answer having been lost. */
    @Test
    fun `a plain 409 is a create stored already`() = runBlocking<Unit> {
        val refusal = writeRefusalOf(status = HttpStatusCode.Conflict, headers = headersOf()) {
            "An expense already exists for this nonce; draw a new one."
        }

        assertEquals(GroupExpenseWriteAnswer.AlreadyExists, refusal)
    }

    @Test
    fun `a 404 the server wrote is a group the caller is not in`() = runBlocking<Unit> {
        assertEquals(
            GroupExpenseWriteAnswer.GroupNotFound,
            writeRefusalOf(status = HttpStatusCode.NotFound, headers = headersOf()) { "Group not found." },
        )
    }

    /** The proxy in front of the server, as it is redeployed: no answer that says anything of the write. */
    @Test
    fun `a 404 the server did not write is left to what it failed with`() = runBlocking<Unit> {
        assertNull(writeRefusalOf(status = HttpStatusCode.NotFound, headers = headersOf()) { "<html>404</html>" })
    }

    @Test
    fun `a 429 lets a write through again once its Retry-After is up`() = runBlocking<Unit> {
        val refusal = writeRefusalOf(
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "42"),
        ) { fail("A 429 has no body to read") }

        assertEquals(GroupExpenseWriteAnswer.RateLimited(retryAfterSeconds = 42), refusal)
    }

    @Test
    fun `any other answer leaves the response unread`() = runBlocking<Unit> {
        listOf(HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.BadRequest, HttpStatusCode.PayloadTooLarge)
            .forEach { status ->
                assertNull(writeRefusalOf(status = status, headers = headersOf()) { fail("Not read for $status") })
            }
    }

    /** As `CreateGroupExpenseRequestBody` and `UpdateGroupExpenseRequestBody` in the API read them. */
    @Test
    fun `the bodies name their fields as the API reads them`() {
        val content = EncryptedFieldDto(ciphertext = "c", nonce = "n")

        val create = Json.encodeToJsonElement(
            CreateGroupExpenseBody.serializer(),
            CreateGroupExpenseBody(nonce = "AAAAAAAAAAAAAAAAAAAAAA==", keyGeneration = 2, content = content),
        ).jsonObject

        val update = Json.encodeToJsonElement(
            UpdateGroupExpenseBody.serializer(),
            UpdateGroupExpenseBody(revision = 3, keyGeneration = 2, content = content),
        ).jsonObject

        assertEquals(setOf("nonce", "key_generation", "content"), create.keys)
        assertEquals(setOf("revision", "key_generation", "content"), update.keys)
    }

    /** As `ListedGroupExpense` and the pages of `GET /groups/{group_uuid}/expenses` in the API write them. */
    @Test
    fun `a listing reads as the API writes it`() {
        val json = Json { ignoreUnknownKeys = true }

        val page = json.decodeFromString(
            ListGroupExpensesResponse.serializer(),
            """{"expenses":[{"uuid":"u","revision":2,"key_generation":1,"content":{"ciphertext":"c","nonce":"n"}}],
                "next_cursor":null,"feed_token":"t"}""",
        )

        val listed = json.decodeFromJsonElement(ListedGroupExpenseDto.serializer(), page.expenses.single())

        assertEquals(ListedGroupExpenseDto(uuid = "u", revision = 2, keyGeneration = 1, content = EncryptedFieldDto("c", "n")), listed)
        assertEquals("t", page.feedToken)

        val changes = json.decodeFromString(
            ListGroupExpenseChangesResponse.serializer(),
            """{"expenses":[],"feed_token":"t2","has_more":true}""",
        )

        assertEquals("t2", changes.feedToken)
        assertEquals(true, changes.hasMore)
    }
}
