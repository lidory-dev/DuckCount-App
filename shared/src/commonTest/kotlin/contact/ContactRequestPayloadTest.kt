package contact

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContactRequestPayloadTest {

    private val welcome = byteArrayOf(1, 2, 3)
    private val message = byteArrayOf(4, 5, 6, 7)
    private val profileEncryptionKey = byteArrayOf(8, 9, 10)

    @Test
    fun `an envelope survives being sent and read back`() {
        val envelope = ContactRequestEnvelope(welcome = welcome, message = message)

        val reread = ContactRequestEnvelope.parse(ciphertext = envelope.serialize())

        assertNotNull(reread)
        assertContentEquals(welcome, reread.welcome)
        assertContentEquals(message, reread.message)
    }

    @Test
    fun `an envelope from a newer build with more fields still opens`() {
        val ciphertext = """{"welcome":"AQID","message":"BAUGBw==","sender_hint":"ignored"}"""

        val reread = ContactRequestEnvelope.parse(ciphertext = ciphertext)

        assertNotNull(reread)
        assertContentEquals(welcome, reread.welcome)
        assertContentEquals(message, reread.message)
    }

    @Test
    fun `a ciphertext that is not an envelope reads as none`() {
        assertNull(ContactRequestEnvelope.parse(ciphertext = "not an envelope"))
        assertNull(ContactRequestEnvelope.parse(ciphertext = """{"welcome":"AQID"}"""))
        assertNull(ContactRequestEnvelope.parse(ciphertext = """{"welcome":"%%%","message":"AQID"}"""))
    }

    @Test
    fun `a card survives being encrypted and read back`() {
        val card = ContactCard(profileEncryptionKey = profileEncryptionKey)

        val reread = ContactCard.parse(plaintext = card.serialize())

        assertNotNull(reread)
        assertContentEquals(profileEncryptionKey, reread.profileEncryptionKey)
    }

    @Test
    fun `a card from a newer build with more fields still opens`() {
        val plaintext = """{"profile_encryption_key":"CAkK","discovery_username":"duck"}"""

        val reread = ContactCard.parse(plaintext = plaintext.encodeToByteArray())

        assertNotNull(reread)
        assertContentEquals(profileEncryptionKey, reread.profileEncryptionKey)
    }

    @Test
    fun `a message that is not a card reads as none`() {
        assertNull(ContactCard.parse(plaintext = "not a card".encodeToByteArray()))
        assertNull(ContactCard.parse(plaintext = "{}".encodeToByteArray()))
    }
}
