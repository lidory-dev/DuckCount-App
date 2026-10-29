package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the history of a group's information is read from the server, see [GroupApi.history]. */
class GroupHistoryResponseTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a history decodes as the server writes it`() {
        val history = json.decodeFromString<GroupHistoryResponse>(
            """{"current":{"revision":3,"title":{"ciphertext":"dDM=","nonce":"bg=="},
            "description":{"ciphertext":"ZDM=","nonce":"bg=="},"signature":{"ciphertext":"czM=","nonce":"bg=="},
            "revised_at":"2026-10-29T08:15:42.317Z"},
            "previous":[{"revision":2,"title":{"ciphertext":"dDI=","nonce":"bg=="},
            "description":{"ciphertext":"ZDI=","nonce":"bg=="},"signature":{"ciphertext":"czI=","nonce":"bg=="},
            "revised_at":"2026-10-28T19:02:07.004Z"},
            {"revision":1,"title":{"ciphertext":"dDE=","nonce":"bg=="},
            "description":{"ciphertext":"ZDE=","nonce":"bg=="},"signature":{"ciphertext":"czE=","nonce":"bg=="},
            "revised_at":"2026-10-27T10:00:00.000Z"}]}""",
        )

        assertEquals(3L, history.current.revision)
        assertEquals(EncryptedFieldDto(ciphertext = "dDM=", nonce = "bg=="), history.current.title)
        assertEquals(EncryptedFieldDto(ciphertext = "ZDM=", nonce = "bg=="), history.current.description)
        assertEquals(EncryptedFieldDto(ciphertext = "czM=", nonce = "bg=="), history.current.signature)
        assertEquals("2026-10-29T08:15:42.317Z", history.current.revisedAt)
        assertEquals(listOf(2L, 1L), history.previous.map { it.revision })
        assertEquals("2026-10-28T19:02:07.004Z", history.previous.first().revisedAt)
    }

    /** As it was created: no edit replaced anything yet. */
    @Test
    fun `a group never edited has no previous version`() {
        val history = json.decodeFromString<GroupHistoryResponse>(
            """{"current":{"revision":1,"title":{"ciphertext":"dA==","nonce":"bg=="},
            "description":{"ciphertext":"ZA==","nonce":"bg=="},"signature":{"ciphertext":"cw==","nonce":"bg=="},
            "revised_at":"2026-10-27T10:00:00.000Z"},"previous":[]}""",
        )

        assertEquals(FIRST_GROUP_REVISION, history.current.revision)
        assertTrue(history.previous.isEmpty())
    }
}
