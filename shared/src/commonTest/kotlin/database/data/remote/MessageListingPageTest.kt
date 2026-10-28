package database.data.remote

import group.ListGroupMessagesResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A page of either listing of messages, `GET /groups/{group_uuid}/messages` and `GET /messages`, as it decodes. */
class MessageListingPageTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    private val message =
        """{"uuid":"8d1e5823-c00c-83b7-8212-500089e3b975","sender_user_uuid":"11111111-1111-1111-1111-111111111111","ciphertext":"Y2lwaGVy","created_at":"2026-10-28T12:00:00.123Z"}"""

    private val cursor = "1761652800123456_8d1e5823-c00c-83b7-8212-500089e3b975"

    @Test
    fun `a page with more after it carries the cursor to ask for the next with`() {
        val group = json.decodeFromString<ListGroupMessagesResponse>("""{"messages":[$message],"next_cursor":"$cursor"}""")
        val direct = json.decodeFromString<ListDirectMessagesResponse>("""{"messages":[$message],"next_cursor":"$cursor"}""")

        assertEquals(cursor, group.nextCursor)
        assertEquals("8d1e5823-c00c-83b7-8212-500089e3b975", group.messages.single().uuid)
        assertEquals(cursor, direct.nextCursor)
        assertEquals("8d1e5823-c00c-83b7-8212-500089e3b975", direct.messages.single().uuid)
    }

    @Test
    fun `the last page carries no cursor`() {
        assertNull(json.decodeFromString<ListGroupMessagesResponse>("""{"messages":[],"next_cursor":null}""").nextCursor)
        assertNull(json.decodeFromString<ListDirectMessagesResponse>("""{"messages":[],"next_cursor":null}""").nextCursor)
    }

    /** As the whole listing is held once read, with no page after it. */
    @Test
    fun `a page without the field reads as the last`() {
        assertNull(json.decodeFromString<ListGroupMessagesResponse>("""{"messages":[$message]}""").nextCursor)
        assertNull(json.decodeFromString<ListDirectMessagesResponse>("""{"messages":[$message]}""").nextCursor)
    }
}
