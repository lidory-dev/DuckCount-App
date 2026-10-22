package database.data.remote

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val ALICE = "3cf93fad-a390-4104-a046-679a62e7ce04"
private const val BOB = "a25df1b3-4124-4164-a2db-1d37da9e8d23"

/**
 * What a response to `POST /expenses` or `PUT /expenses/{uuid}` says beyond what the client reads
 * it as, see [writeRefusalOf].
 */
class OneTimeExpenseWriteRefusalTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a 410 names everybody it says deleted their account`() = runBlocking<Unit> {
        val refusal = writeRefusalOf(status = HttpStatusCode.Gone) {
            json.decodeFromString("""{"deleted_user_uuids":["$ALICE","$BOB"]}""")
        }

        assertEquals(WriteOneTimeExpenseError.BeneficiariesDeleted(userUuids = listOf(ALICE, BOB)), refusal)
    }

    /** A `409` from either route has no body to read: only a `410` names anybody. */
    @Test
    fun `any other refusal leaves the body unread`() = runBlocking<Unit> {
        assertNull(writeRefusalOf(status = HttpStatusCode.Conflict) { fail("Only a 410 has a body to read") })
    }

    @Test
    fun `an expense taken says nothing more`() = runBlocking<Unit> {
        assertNull(writeRefusalOf(status = HttpStatusCode.Created) { fail("Only a 410 has a body to read") })
    }
}
