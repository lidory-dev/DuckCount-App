package message

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DirectMessageEnvelopeTest {

    /** The size of every group id `lib-duckcount` creates or joins. */
    private val mlsGroupId = ByteArray(32) { it.toByte() }
    private val encodedMlsGroupId = Base64.Default.encode(mlsGroupId)
    private val message = byteArrayOf(4, 5, 6, 7)
    private val welcome = byteArrayOf(8, 9, 10, 11)

    @Test
    fun `a direct message survives being sent and read back`() {
        val envelope = DirectMessageEnvelope(mlsGroupId = mlsGroupId, message = message)

        val reread = DirectMessageEnvelope.parse(ciphertext = envelope.serialize())

        assertNotNull(reread)
        assertContentEquals(mlsGroupId, reread.mlsGroupId)
        assertContentEquals(message, reread.message)
        assertNull(reread.welcome)
    }

    @Test
    fun `a welcome survives being sent and read back alongside the message`() {
        val envelope = DirectMessageEnvelope(
            mlsGroupId = mlsGroupId,
            message = message,
            welcome = welcome,
        )

        val reread = DirectMessageEnvelope.parse(ciphertext = envelope.serialize())

        assertNotNull(reread)
        assertContentEquals(welcome, reread.welcome)
    }

    /** The receiving client reads the group id under this name, whichever build sent it. */
    @Test
    fun `a direct message names the group the way its reader reads it`() {
        val ciphertext = """{"mls_group_id":"$encodedMlsGroupId","message":"BAUGBw==","sent_at":"ignored"}"""

        val reread = DirectMessageEnvelope.parse(ciphertext = ciphertext)

        assertNotNull(reread)
        assertContentEquals(mlsGroupId, reread.mlsGroupId)
        assertContentEquals(message, reread.message)
    }

    /**
     * A build that predates the Welcome field wrote envelopes without one, and they have to
     * keep reading as the ordinary messages they are rather than as malformed.
     */
    @Test
    fun `an envelope written before welcomes existed still reads`() {
        val reread = DirectMessageEnvelope.parse(
            ciphertext = """{"mls_group_id":"$encodedMlsGroupId","message":"BAUGBw=="}""",
        )

        assertNotNull(reread)
        assertNull(reread.welcome)
    }

    /** No group this device holds has an id of another size, which the sender chooses freely. */
    @Test
    fun `a direct message naming a group of another size reads as none`() {
        for (size in listOf(3, 33, 4096)) {
            val envelope = DirectMessageEnvelope(mlsGroupId = ByteArray(size), message = message)

            assertNull(DirectMessageEnvelope.parse(ciphertext = envelope.serialize()))
        }
    }

    @Test
    fun `a ciphertext that is not a direct message reads as none`() {
        assertNull(DirectMessageEnvelope.parse(ciphertext = "not a direct message"))
        assertNull(DirectMessageEnvelope.parse(ciphertext = """{"message":"AQID"}"""))
        assertNull(DirectMessageEnvelope.parse(ciphertext = """{"welcome":"AQID","message":"BAUGBw=="}"""))
    }
}
