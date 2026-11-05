package database.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneTimeExpenseListingResponseTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    private val sealed = """{"ciphertext":"Y2lwaGVy","nonce":"bm9uY2U="}"""

    /** An expense as the server lists it, with a field this build has never heard of. */
    private val expense = """
        {"uuid":"8d1e5823-c00c-83b7-8212-500089e3b975","signature":$sealed,
         "creditor_uuid":"01234567-89ab-4cde-8f01-23456789abcd","title":$sealed,
         "description":$sealed,"amount":$sealed,"currency":$sealed,"timestamp":$sealed,
         "beneficiaries":[{"user_uuid":"11111111-1111-1111-1111-111111111111","share":$sealed,"is_share_fixed":$sealed,"share_percentage":$sealed,"share_weight":$sealed}],
         "key_copy":null,"is_settled":null,"added_later":{"by":"a newer server"}}
    """.trimIndent()

    @Test
    fun `a listing page decodes and its first page says where the feed stood`() {
        val first = json.decodeFromString<ListOneTimeExpensesResponse>(
            """{"expenses":[$expense],"next_cursor":"1759926896123456_8d1e5823-c00c-83b7-8212-500089e3b975","feed_token":"0f7b3c2e-6a1d-4c8e-9b5a-2d4e6f8a0b1c_42"}""",
        )
        val later = json.decodeFromString<ListOneTimeExpensesPage>(
            """{"expenses":[],"next_cursor":null,"feed_token":null}""",
        )

        assertEquals(1, first.expenses.size)
        assertEquals("0f7b3c2e-6a1d-4c8e-9b5a-2d4e6f8a0b1c_42", first.feed_token)
        assertNull(later.next_cursor)
    }

    @Test
    fun `a page of changes decodes`() {
        val changes = json.decodeFromString<ListOneTimeExpenseChangesResponse>(
            """{"expenses":[$expense],"removed":["22222222-2222-2222-2222-222222222222"],"feed_token":"0f7b3c2e-6a1d-4c8e-9b5a-2d4e6f8a0b1c_43","has_more":false}""",
        )

        assertEquals(1, changes.expenses.size)
        assertEquals(listOf("22222222-2222-2222-2222-222222222222"), changes.removed)
        assertEquals(false, changes.has_more)
    }

    /**
     * What the device keeps of an expense is the JSON the server wrote, so that a newer
     * build finds in it what this one ignored.
     */
    @Test
    fun `an expense is kept with what this build does not read of it`() {
        val page = json.decodeFromString<ListOneTimeExpensesPage>(
            """{"expenses":[$expense],"next_cursor":null,"feed_token":null}""",
        )

        val kept = json.parseToJsonElement(page.expenses.single().toString()).jsonObject

        assertEquals(JsonPrimitive("a newer server"), kept["added_later"]?.jsonObject?.get("by"))
        assertEquals(
            "8d1e5823-c00c-83b7-8212-500089e3b975",
            json.decodeFromJsonElement<ExpenseDto>(kept).uuid,
        )
    }
}
