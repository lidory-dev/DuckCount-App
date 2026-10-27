package group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How the new key of a group is handed to its members after a removal, see [GroupKeyMessage]. */
class GroupKeyMessageTest {

    private val groupKey = ByteArray(32) { it.toByte() }

    @Test
    fun `a key message names itself and carries the key`() {
        val written = Json.parseToJsonElement(groupKeyMessageOf(groupKey).decodeToString()).jsonObject

        assertEquals(setOf("type", "key"), written.keys)
        assertEquals("group_key", written.getValue("type").jsonPrimitive.content)
        assertEquals(Base64.Default.encode(groupKey), written.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun `a key message reads back as the key it hands over`() {
        assertContentEquals(groupKey, groupKeyOf(groupKeyMessageOf(groupKey)))
    }

    /** Whatever else comes to be sent in a group one day. */
    @Test
    fun `a message of another type hands over no key`() {
        val key = Base64.Default.encode(groupKey)

        assertNull(groupKeyOf("""{"type":"expense_key","key":"$key"}""".encodeToByteArray()))
    }

    @Test
    fun `a key that is not 32 bytes is no group key`() {
        assertNull(groupKeyOf(groupKeyMessageOf(ByteArray(16))))
    }

    @Test
    fun `a payload that is not a key message hands over no key`() {
        listOf(
            "not json",
            """{"type":"group_key"}""",
            """{"type":"group_key","key":"not base64!"}""",
            "",
        ).forEach { payload ->
            assertNull(groupKeyOf(payload.encodeToByteArray()))
        }
    }
}
