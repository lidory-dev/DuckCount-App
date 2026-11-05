package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockListTest {

    private val mallory = "77777777-7777-7777-7777-777777777777"
    private val trent = "88888888-8888-8888-8888-888888888888"

    @Test
    fun `a list read back holds everybody blocked with their name`() {
        val list = BlockList.EMPTY.withChanges(blocked = mapOf(mallory to "Mallory", trent to null), unblocked = emptySet())

        val read = assertNotNull(BlockList.parse(plaintext = list.serialize()))

        assertEquals(mapOf(mallory to "Mallory", trent to null), read.blocked)
    }

    @Test
    fun `somebody unblocked goes from the list`() {
        val list = BlockList.EMPTY
            .withChanges(blocked = mapOf(mallory to "Mallory", trent to "Trent"), unblocked = emptySet())
            .withChanges(blocked = emptyMap(), unblocked = setOf(mallory))

        assertEquals(mapOf(trent to "Trent"), list.blocked)
    }

    /** Blocking them again with no name to show is no reason to forget the one another device had. */
    @Test
    fun `blocking somebody again with no name keeps the one the list holds`() {
        val list = BlockList.EMPTY
            .withChanges(blocked = mapOf(mallory to "Mallory"), unblocked = emptySet())
            .withChanges(blocked = mapOf(mallory to null), unblocked = emptySet())

        assertEquals("Mallory", list.blocked[mallory])
    }

    /** A newer build may add a field an older one running somewhere must not drop as it blocks somebody. */
    @Test
    fun `blocking somebody keeps the fields this build does not know`() {
        val written = JsonObject(
            mapOf(mallory to JsonObject(mapOf("full_name" to JsonPrimitive("Mallory"), "since" to JsonPrimitive(1)))),
        )

        val list = assertNotNull(BlockList.parse(plaintext = written.toString().encodeToByteArray()))
            .withChanges(blocked = mapOf(mallory to "Mal", trent to "Trent"), unblocked = emptySet())

        val entry = Json.parseToJsonElement(list.serialize().decodeToString().trimEnd()).jsonObject.getValue(mallory).jsonObject

        assertEquals(JsonPrimitive(1), entry["since"])
        assertEquals(JsonPrimitive("Mal"), entry["full_name"])
    }

    /** Its length says no more than which of the book sizes it is, see `paddedToBookSize`. */
    @Test
    fun `a list is sealed at the size of a book`() {
        val one = BlockList.EMPTY.withChanges(blocked = mapOf(mallory to "Mallory"), unblocked = emptySet())
        val two = one.withChanges(blocked = mapOf(trent to "Trent"), unblocked = emptySet())

        assertEquals(16 * 1024, one.serialize().size)
        assertEquals(16 * 1024, two.serialize().size)
        assertEquals(16 * 1024, BlockList.EMPTY.serialize().size)
    }

    @Test
    fun `bytes that are no JSON object are no list`() {
        assertNull(BlockList.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(BlockList.parse(plaintext = byteArrayOf(-1, -2)))
        assertTrue(assertNotNull(BlockList.parse(plaintext = "{}".encodeToByteArray())).blocked.isEmpty())
    }
}
