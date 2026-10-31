package group

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** What an edit of a group's information is read as off the response itself, see [editRefusalOf]. */
class GroupEditAnswerTest {

    /** Commits this device has yet to read: the edit is sealed again under the key they lead to. */
    @Test
    fun `a JSON 409 is a version sealed under a generation the group left`() = runBlocking<Unit> {
        val refusal = editRefusalOf(status = HttpStatusCode.Conflict) { """{"key_generation":4}""" }

        assertEquals(GroupEditAnswer.KeyGenerationNotCurrent(keyGeneration = 4), refusal)
    }

    /** An earlier edit whose answer was lost, or one built on a version replaced since. */
    @Test
    fun `a plain 409 is a revision the server holds or is past`() = runBlocking<Unit> {
        val refusal = editRefusalOf(status = HttpStatusCode.Conflict) {
            "The group's information is at this revision or past it already."
        }

        assertEquals(GroupEditAnswer.RevisionNotNewer, refusal)
    }

    @Test
    fun `any other answer leaves the response unread`() = runBlocking<Unit> {
        listOf(HttpStatusCode.NoContent, HttpStatusCode.Forbidden, HttpStatusCode.NotFound).forEach { status ->
            assertNull(editRefusalOf(status = status) { fail("Only a 409 has a body to read") })
        }
    }
}
