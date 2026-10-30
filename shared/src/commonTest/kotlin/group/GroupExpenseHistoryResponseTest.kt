package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the history of a group expense is read from the server, see [GroupExpenseApi.history]. */
class GroupExpenseHistoryResponseTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a history decodes as the server writes it`() {
        val history = json.decodeFromString<GroupExpenseHistoryResponse>(
            """{"current":{"revision":3,"key_generation":2,"content":{"ciphertext":"YzM=","nonce":"bg=="},
            "revised_at":"2026-10-29T08:15:42.317Z"},
            "previous":[{"revision":2,"key_generation":2,"content":{"ciphertext":"YzI=","nonce":"bg=="},
            "revised_at":"2026-10-28T19:02:07.004Z"},
            {"revision":1,"key_generation":1,"content":{"ciphertext":"YzE=","nonce":"bg=="},
            "revised_at":"2026-10-27T10:00:00.000Z"}]}""",
        )

        assertEquals(3L, history.current.revision)
        assertEquals(2L, history.current.keyGeneration)
        assertEquals(EncryptedFieldDto(ciphertext = "YzM=", nonce = "bg=="), history.current.content)
        assertEquals("2026-10-29T08:15:42.317Z", history.current.revisedAt)
        assertEquals(listOf(2L, 1L), history.previous.map { it.revision })
        assertEquals(listOf(2L, 1L), history.previous.map { it.keyGeneration })
        assertEquals(listOf("2026-10-28T19:02:07.004Z", "2026-10-27T10:00:00.000Z"), history.previous.map { it.revisedAt })
    }

    /** As it was created: no update replaced anything yet. */
    @Test
    fun `an expense never updated has no previous version`() {
        val history = json.decodeFromString<GroupExpenseHistoryResponse>(
            """{"current":{"revision":1,"key_generation":1,"content":{"ciphertext":"Yw==","nonce":"bg=="},
            "revised_at":"2026-10-27T10:00:00.000Z"},"previous":[]}""",
        )

        assertEquals(FIRST_GROUP_EXPENSE_REVISION, history.current.revision)
        assertTrue(history.previous.isEmpty())
    }
}
