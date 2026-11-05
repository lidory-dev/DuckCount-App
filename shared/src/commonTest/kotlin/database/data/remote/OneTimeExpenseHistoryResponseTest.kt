package database.data.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneTimeExpenseHistoryResponseTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    private val sealed = """{"ciphertext":"Y2lwaGVy","nonce":"bm9uY2U="}"""

    @Test
    fun `a history decodes as the server writes it`() {
        val history = json.decodeFromString<List<OneTimeExpenseHistoryEntryDto>>(
            """
            [{"modifier_user_uuid":"01234567-89ab-4cde-8f01-23456789abcd",
              "modified_at":"2026-10-13T08:15:42.317Z",
              "previous_signature":$sealed,"previous_title":$sealed,"previous_description":$sealed,
              "previous_amount":$sealed,"previous_currency":$sealed,"previous_timestamp":$sealed,
              "previous_beneficiaries":[{"user_uuid":"11111111-1111-1111-1111-111111111111","share":$sealed,"is_share_fixed":$sealed,"share_percentage":$sealed,"share_weight":$sealed}]}]
            """.trimIndent(),
        )

        assertEquals("2026-10-13T08:15:42.317Z", history.single().modified_at)
        assertEquals("11111111-1111-1111-1111-111111111111", history.single().previous_beneficiaries.single().user_uuid)
    }

    @Test
    fun `an expense never edited has an empty history`() {
        assertTrue(json.decodeFromString<List<OneTimeExpenseHistoryEntryDto>>("[]").isEmpty())
    }
}
