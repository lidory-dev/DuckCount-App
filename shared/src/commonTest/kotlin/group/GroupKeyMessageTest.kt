package group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** How the new key of a group is handed to its members after a removal, see [GroupKeyMessage]. */
class GroupKeyMessageTest {

    private val groupKey = ByteArray(32) { it.toByte() }

    @Test
    fun `a key message names itself and carries the key and its generation`() {
        val written = Json.parseToJsonElement(groupKeyMessageOf(groupKey, keyGeneration = 4).decodeToString()).jsonObject

        assertEquals(setOf("type", "key", "key_generation"), written.keys)
        assertEquals("group_key", written.getValue("type").jsonPrimitive.content)
        assertEquals(Base64.Default.encode(groupKey), written.getValue("key").jsonPrimitive.content)
        assertEquals("4", written.getValue("key_generation").jsonPrimitive.content)
    }

    @Test
    fun `a key message reads back as the key it hands over and its generation`() {
        val handed = assertNotNull(groupKeyOf(groupKeyMessageOf(groupKey, keyGeneration = 4)))

        assertContentEquals(groupKey, handed.key)
        assertEquals(4L, handed.generation)
    }

    /** Whatever else comes to be sent in a group one day. */
    @Test
    fun `a message of another type hands over no key`() {
        val key = Base64.Default.encode(groupKey)

        assertNull(groupKeyOf("""{"type":"expense_key","key":"$key","key_generation":2}""".encodeToByteArray()))
    }

    @Test
    fun `a key that is not 32 bytes is no group key`() {
        assertNull(groupKeyOf(groupKeyMessageOf(ByteArray(16), keyGeneration = 2)))
    }

    /** The first is the one the group is created with, see [FIRST_KEY_GENERATION]. */
    @Test
    fun `a key of no generation of the group's is no group key`() {
        assertNull(groupKeyOf(groupKeyMessageOf(groupKey, keyGeneration = 0)))
    }

    @Test
    fun `a payload that is not a key message hands over no key`() {
        val key = Base64.Default.encode(groupKey)

        listOf(
            "not json",
            """{"type":"group_key"}""",
            """{"type":"group_key","key":"not base64!","key_generation":2}""",
            """{"type":"group_key","key":"$key"}""",
            "",
        ).forEach { payload ->
            assertNull(groupKeyOf(payload.encodeToByteArray()))
        }
    }
}
