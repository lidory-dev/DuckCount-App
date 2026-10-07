package database.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactRequestStatusResponseTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every status the server answers decodes`() {
        mapOf(
            "pending" to ContactRequestStatusDto.PENDING,
            "accepted" to ContactRequestStatusDto.ACCEPTED,
            "gone" to ContactRequestStatusDto.GONE,
        ).forEach { (wire, status) ->
            val response = json.decodeFromString<ContactRequestStatusResponse>("""{"status":"$wire"}""")

            assertEquals(status, response.status)
        }
    }

    @Test
    fun `a status this build does not know is no answer`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ContactRequestStatusResponse>("""{"status":"withdrawn"}""")
        }
    }
}
