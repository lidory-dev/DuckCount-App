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

    @Test
    fun `an empty book holds no contact`() {
        assertTrue(ContactBook.EMPTY.profileEncryptionKeys.isEmpty())
    }

    @Test
    fun `contacts survive being sealed and read back`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, profileEncryptionKey = aliceKey)
            .with(userUuid = bob, profileEncryptionKey = bobKey)

        val reread = ContactBook.parse(plaintext = book.serialize())!!.profileEncryptionKeys

        assertEquals(setOf(alice, bob), reread.keys)
        assertContentEquals(aliceKey, reread[alice])
        assertContentEquals(bobKey, reread[bob])
    }

    @Test
    fun `adding a contact already in the book replaces their key`() {
        val replacement = byteArrayOf(9, 9)

        val book = ContactBook.EMPTY
            .with(userUuid = alice, profileEncryptionKey = aliceKey)
            .with(userUuid = alice, profileEncryptionKey = replacement)

        assertEquals(setOf(alice), book.profileEncryptionKeys.keys)
        assertContentEquals(replacement, book.profileEncryptionKeys[alice])
    }

    @Test
    fun `a removed contact is gone and the others stay`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, profileEncryptionKey = aliceKey)
            .with(userUuid = bob, profileEncryptionKey = bobKey)
            .without(userUuid = alice)

        val reread = ContactBook.parse(plaintext = book.serialize())!!.profileEncryptionKeys

        assertEquals(setOf(bob), reread.keys)
        assertContentEquals(bobKey, reread[bob])
    }

    /**
     * A removal that already reached the vault can be pushed again, when the device lost
     * track of it having gone through, so removing somebody absent has to be harmless.
     */
    @Test
    fun `removing somebody who is not a contact changes nothing`() {
        val book = ContactBook.EMPTY.with(userUuid = alice, profileEncryptionKey = aliceKey)

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
            .with(userUuid = alice, profileEncryptionKey = aliceKey)
            .with(userUuid = bob, profileEncryptionKey = bobKey)
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

        val keys = ContactBook.parse(plaintext = plaintext)!!.profileEncryptionKeys

        assertEquals(setOf(alice), keys.keys)
    }

    @Test
    fun `plaintext that is not a JSON object does not parse`() {
        assertNull(ContactBook.parse(plaintext = "not a contact book".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = byteArrayOf()))
    }
}
