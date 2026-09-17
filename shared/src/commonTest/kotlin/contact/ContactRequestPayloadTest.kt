package contact

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a contact message survives being sent and read back`() {
        val envelope = ContactMessageEnvelope(mlsGroupId = welcome, message = message)

        val reread = ContactMessageEnvelope.parse(ciphertext = envelope.serialize())

        assertNotNull(reread)
        assertContentEquals(welcome, reread.mlsGroupId)
        assertContentEquals(message, reread.message)
    }

    /** The receiving client reads the group id under this name, whichever build sent it. */
    @Test
    fun `a contact message names the group the way its reader reads it`() {
        val ciphertext = """{"mls_group_id":"AQID","message":"BAUGBw==","sent_at":"ignored"}"""

        val reread = ContactMessageEnvelope.parse(ciphertext = ciphertext)

        assertNotNull(reread)
        assertContentEquals(welcome, reread.mlsGroupId)
        assertContentEquals(message, reread.message)
    }

    @Test
    fun `a ciphertext that is not a contact message reads as none`() {
        assertNull(ContactMessageEnvelope.parse(ciphertext = "not a contact message"))
        assertNull(ContactMessageEnvelope.parse(ciphertext = """{"message":"AQID"}"""))
        assertNull(ContactMessageEnvelope.parse(ciphertext = """{"welcome":"AQID","message":"BAUGBw=="}"""))
    }

    @Test
    fun `a removal survives being encrypted and read back`() {
        assertTrue(ContactRemoval.matches(plaintext = ContactRemoval.serialize()))
    }

    /**
     * A contact card and a removal travel the same way, so neither may ever be taken for the
     * other: a card read as a removal would drop the very contact it was making.
     */
    @Test
    fun `a contact card and a removal are never taken for each other`() {
        val card = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize()

        assertFalse(ContactRemoval.matches(plaintext = card))
        assertNull(ContactCard.parse(plaintext = ContactRemoval.serialize()))
    }

    @Test
    fun `a message that is not a removal is not taken for one`() {
        assertFalse(ContactRemoval.matches(plaintext = "not a removal".encodeToByteArray()))
        assertFalse(ContactRemoval.matches(plaintext = "{}".encodeToByteArray()))
        assertFalse(ContactRemoval.matches(plaintext = """{"type":"something_else"}""".encodeToByteArray()))
        assertTrue(ContactRemoval.matches(plaintext = """{"type":"contact_removal","at":1}""".encodeToByteArray()))
    }
}
