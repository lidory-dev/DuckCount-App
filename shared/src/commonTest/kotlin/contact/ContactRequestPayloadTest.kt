package contact

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test
    fun `a profile key update survives being encrypted and read back`() {
        val update = ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey)

        val reread = ProfileKeyUpdate.parse(plaintext = update.serialize())

        assertNotNull(reread)
        assertContentEquals(profileEncryptionKey, reread.profileEncryptionKey)
    }

    @Test
    fun `an update from a newer build with more fields still opens`() {
        val plaintext = """{"type":"profile_key","profile_encryption_key":"CAkK","rotated_at":1}"""

        val reread = ProfileKeyUpdate.parse(plaintext = plaintext.encodeToByteArray())

        assertNotNull(reread)
        assertContentEquals(profileEncryptionKey, reread.profileEncryptionKey)
    }

    @Test
    fun `a message that is not an update reads as none`() {
        assertNull(ProfileKeyUpdate.parse(plaintext = "not an update".encodeToByteArray()))
        assertNull(ProfileKeyUpdate.parse(plaintext = "{}".encodeToByteArray()))
        assertNull(ProfileKeyUpdate.parse(plaintext = """{"type":"profile_key"}""".encodeToByteArray()))
        assertNull(
            ProfileKeyUpdate.parse(
                plaintext = """{"type":"profile_key","profile_encryption_key":"%%%"}""".encodeToByteArray(),
            ),
        )
        assertNull(
            ProfileKeyUpdate.parse(
                plaintext = """{"type":"something_else","profile_encryption_key":"CAkK"}""".encodeToByteArray(),
            ),
        )
        assertNull(ProfileKeyUpdate.parse(plaintext = ContactRemoval.serialize()))
    }

    /**
     * A card carries a key under the same name as an update does, and is recognised by that
     * alone, so the update reads as a card too: what tells them apart is that the update is
     * read first. Taken for a card, it would answer no request and its key would be lost.
     */
    @Test
    fun `a profile key update is not taken for a contact card`() {
        val update = ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey).serialize()

        val message = assertIs<ContactMessage.ProfileKey>(ContactMessage.read(payload = update))

        assertContentEquals(profileEncryptionKey, message.update.profileEncryptionKey)
    }

    /**
     * A card answering a request comes from somebody who is not a contact yet. Taken for an
     * update, it would be dropped as one from a stranger, and the request it answers never be:
     * they would not become a contact, and their key would be lost.
     */
    @Test
    fun `a contact card is not taken for a profile key update`() {
        val card = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize()

        assertNull(ProfileKeyUpdate.parse(plaintext = card))

        val message = assertIs<ContactMessage.Answer>(ContactMessage.read(payload = card))

        assertContentEquals(profileEncryptionKey, message.card.profileEncryptionKey)
    }

    @Test
    fun `a profile key update and a removal are never taken for each other`() {
        val update = ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey).serialize()

        assertFalse(ContactRemoval.matches(plaintext = update))
        assertSame(ContactMessage.Removal, ContactMessage.read(payload = ContactRemoval.serialize()))
    }

    @Test
    fun `a profile key request survives being encrypted and read back`() {
        assertTrue(ProfileKeyRequest.matches(plaintext = ProfileKeyRequest.serialize()))
        assertTrue(ProfileKeyRequest.matches(plaintext = """{"type":"profile_key_request","at":1}""".encodeToByteArray()))
    }

    @Test
    fun `a message that is not a profile key request is not taken for one`() {
        assertFalse(ProfileKeyRequest.matches(plaintext = "not a request".encodeToByteArray()))
        assertFalse(ProfileKeyRequest.matches(plaintext = "{}".encodeToByteArray()))
        assertFalse(ProfileKeyRequest.matches(plaintext = ContactRemoval.serialize()))
        assertFalse(ProfileKeyRequest.matches(plaintext = ProfileKeyUpdate(profileEncryptionKey).serialize()))
        assertFalse(ProfileKeyRequest.matches(plaintext = ContactCard(profileEncryptionKey).serialize()))
    }

    /** Taken for an update, a request would hand over no key; taken for a removal, end the contact. */
    @Test
    fun `a profile key request is taken for nothing else`() {
        val request = ProfileKeyRequest.serialize()

        assertSame(ContactMessage.KeyRequest, ContactMessage.read(payload = request))
        assertNull(ProfileKeyUpdate.parse(plaintext = request))
        assertNull(ContactCard.parse(plaintext = request))
        assertFalse(ContactRemoval.matches(plaintext = request))
    }

    @Test
    fun `a notice of account deletion survives being encrypted and read back`() {
        assertTrue(AccountDeletionNotice.matches(plaintext = AccountDeletionNotice.serialize()))
        assertTrue(AccountDeletionNotice.matches(plaintext = """{"type":"account_deletion","at":1}""".encodeToByteArray()))
    }

    @Test
    fun `a message that is not a notice of account deletion is not taken for one`() {
        assertFalse(AccountDeletionNotice.matches(plaintext = "not a notice".encodeToByteArray()))
        assertFalse(AccountDeletionNotice.matches(plaintext = "{}".encodeToByteArray()))
        assertFalse(AccountDeletionNotice.matches(plaintext = ContactRemoval.serialize()))
        assertFalse(AccountDeletionNotice.matches(plaintext = ProfileKeyRequest.serialize()))
        assertFalse(AccountDeletionNotice.matches(plaintext = ProfileKeyUpdate(profileEncryptionKey).serialize()))
        assertFalse(AccountDeletionNotice.matches(plaintext = ContactCard(profileEncryptionKey).serialize()))
    }

    @Test
    fun `a notice of account deletion is taken for nothing else and a removal is not taken for it`() {
        val notice = AccountDeletionNotice.serialize()

        assertSame(ContactMessage.AccountDeleted, ContactMessage.read(payload = notice))
        assertFalse(ContactRemoval.matches(plaintext = notice))
        assertNull(ContactCard.parse(plaintext = notice))
        assertNull(ProfileKeyUpdate.parse(plaintext = notice))
        assertFalse(ProfileKeyRequest.matches(plaintext = notice))

        assertSame(ContactMessage.Removal, ContactMessage.read(payload = ContactRemoval.serialize()))
    }

    @Test
    fun `a message about something else is no contact message`() {
        assertNull(ContactMessage.read(payload = "not a contact message".encodeToByteArray()))
        assertNull(ContactMessage.read(payload = """{"type":"something_else"}""".encodeToByteArray()))
    }
}
