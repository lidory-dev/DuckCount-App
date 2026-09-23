package vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactBookTest {

    private val alice = "136f99cf-f4dd-4b2e-a03d-6c42f47343f6"
    private val bob = "003c8dd5-1f21-44ac-8090-afdaeb04cd66"

    private val aliceKey = byteArrayOf(1, 2, 3)
    private val bobKey = byteArrayOf(4, 5, 6)
    private val aliceSignatureKey = byteArrayOf(7, 7, 7)

    private fun contact(profileEncryptionKey: ByteArray, mlsSignaturePublicKey: ByteArray? = null) =
        VaultContact(
            profileEncryptionKey = profileEncryptionKey,
            mlsSignaturePublicKey = mlsSignaturePublicKey,
        )

    @Test
    fun `an empty book holds no contact`() {
        assertTrue(ContactBook.EMPTY.contacts.isEmpty())
    }

    @Test
    fun `contacts survive being sealed and read back`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(setOf(alice, bob), reread.keys)
        assertContentEquals(aliceKey, reread[alice]?.profileEncryptionKey)
        assertContentEquals(bobKey, reread[bob]?.profileEncryptionKey)
    }

    @Test
    fun `adding a contact already in the book replaces their key`() {
        val replacement = byteArrayOf(9, 9)

        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))
            .with(userUuid = alice, contact = contact(profileEncryptionKey = replacement))

        assertEquals(setOf(alice), book.contacts.keys)
        assertContentEquals(replacement, book.contacts[alice]?.profileEncryptionKey)
    }

    @Test
    fun `a removed contact is gone and the others stay`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))
            .without(userUuid = alice)

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(setOf(bob), reread.keys)
        assertContentEquals(bobKey, reread[bob]?.profileEncryptionKey)
    }

    /**
     * A removal that already reached the vault can be pushed again, when the device lost
     * track of it having gone through, so removing somebody absent has to be harmless.
     */
    @Test
    fun `removing somebody who is not a contact changes nothing`() {
        val book = ContactBook.EMPTY.with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))

        val rewritten = book.without(userUuid = bob)

        assertContentEquals(book.serialize(), rewritten.serialize())
    }

    @Test
    fun `what this build does not know survives removing a contact`() {
        val plaintext = """
            {
                "$alice": {"profile_encryption_key": "AQID"},
                "$bob": {"profile_encryption_key": "BAUG", "nickname": "Bobby"},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val rewritten = ContactBook.parse(plaintext = plaintext)!!
            .without(userUuid = alice)
            .serialize()
            .decodeToString()

        assertTrue(rewritten.contains("\"nickname\":\"Bobby\""), "the unknown field was dropped: $rewritten")
        assertTrue(
            rewritten.contains("\"a_newer_kind_of_entry\":[1,2]"),
            "the unknown entry was dropped: $rewritten",
        )
        assertTrue(!rewritten.contains(alice), "the removed contact is still there: $rewritten")
    }

    /**
     * Same rule as the key ring's: whatever a newer app version stored must come back out
     * of an older version's write, or adding a contact would quietly destroy it.
     */
    @Test
    fun `what this build does not know survives adding a contact`() {
        val plaintext = """
            {
                "$alice": {"profile_encryption_key": "AQID", "nickname": "Al"},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val rewritten = ContactBook.parse(plaintext = plaintext)!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))
            .serialize()
            .decodeToString()

        assertTrue(rewritten.contains("\"nickname\":\"Al\""), "the unknown field was dropped: $rewritten")
        assertTrue(
            rewritten.contains("\"a_newer_kind_of_entry\":[1,2]"),
            "the unknown entry was dropped: $rewritten",
        )
    }

    @Test
    fun `an entry without a readable key is not a contact`() {
        val plaintext = """
            {
                "$alice": {"profile_encryption_key": "AQID"},
                "$bob": {"profile_encryption_key": {"nested": "value"}},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val keys = ContactBook.parse(plaintext = plaintext)!!.contacts

        assertEquals(setOf(alice), keys.keys)
    }

    @Test
    fun `a pinned identity survives being sealed and read back`() {
        val book = ContactBook.EMPTY.with(
            userUuid = alice,
            contact = contact(
                profileEncryptionKey = aliceKey,
                mlsSignaturePublicKey = aliceSignatureKey,
            ),
        )

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertContentEquals(aliceSignatureKey, reread[alice]?.mlsSignaturePublicKey)
    }

    /**
     * A device that has never reached a contact over MLS knows nothing about their
     * identity, and knowing nothing must not erase what another device recorded.
     */
    @Test
    fun `writing a contact without a pinned identity keeps the one already stored`() {
        val book = ContactBook.EMPTY
            .with(
                userUuid = alice,
                contact = contact(
                    profileEncryptionKey = aliceKey,
                    mlsSignaturePublicKey = aliceSignatureKey,
                ),
            )
            .with(userUuid = alice, contact = contact(profileEncryptionKey = bobKey))

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertContentEquals(bobKey, reread[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, reread[alice]?.mlsSignaturePublicKey)
    }

    /** A book written before pins travelled with it still reads. */
    @Test
    fun `a contact with no pinned identity is still a contact`() {
        val plaintext = """{"$alice": {"profile_encryption_key": "AQID"}}""".encodeToByteArray()

        val reread = ContactBook.parse(plaintext = plaintext)!!.contacts

        assertEquals(setOf(alice), reread.keys)
        assertNull(reread[alice]?.mlsSignaturePublicKey)
    }

    @Test
    fun `plaintext that is not a JSON object does not parse`() {
        assertNull(ContactBook.parse(plaintext = "not a contact book".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = byteArrayOf()))
    }
}
