package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactBookTest {

    private val alice = "136f99cf-f4dd-4b2e-a03d-6c42f47343f6"
    private val bob = "003c8dd5-1f21-44ac-8090-afdaeb04cd66"

    private val aliceKey = byteArrayOf(1, 2, 3)
    private val bobKey = byteArrayOf(4, 5, 6)
    private val aliceSignatureKey = byteArrayOf(7, 7, 7)

    /** Partial hashes of two of the user's own profile keys, the older first. */
    private val handedBefore = "0123456789"
    private val handedNow = "abcdef0123"

    /** The safety number the user verified with Alice, as the library renders one. */
    private val aliceSafetyNumber = "33970 27467 27504 88066 45223 52066 50081 20585 20677 07852 00402 61184"

    private fun contact(
        profileEncryptionKey: ByteArray,
        mlsSignaturePublicKey: ByteArray? = null,
        handedProfileKeyHash: String? = null,
        verifiedSafetyNumber: String? = null,
    ) =
        VaultContact(
            profileEncryptionKey = profileEncryptionKey,
            mlsSignaturePublicKey = mlsSignaturePublicKey,
            handedProfileKeyHash = handedProfileKeyHash,
            verifiedSafetyNumber = verifiedSafetyNumber,
        )

    /** A book of [count] contacts as large as one gets, verified and pinned, each with their own UUID. */
    private fun bookOf(count: Int): ContactBook =
        (0 until count).fold(ContactBook.EMPTY) { book, index ->
            assertNotNull(
                book.with(
                    userUuid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    contact = contact(
                        profileEncryptionKey = ByteArray(32),
                        mlsSignaturePublicKey = ByteArray(32),
                        handedProfileKeyHash = "0123456789",
                        verifiedSafetyNumber = "1".repeat(60),
                    ),
                ),
            )
        }

    /** A book whose JSON takes exactly [size] bytes, an unknown field of its one entry making up the size. */
    private fun bookTaking(size: Int): ContactBook {
        val json = "{\"a\":{\"f\":\"\"}}"

        return assertNotNull(
            ContactBook.parse(json.replace("\"\"", "\"" + "x".repeat(size - json.length) + "\"").encodeToByteArray()),
        )
    }

    /** Its length would otherwise say how many contacts the user has, to the contact. */
    @Test
    fun `a book seals to the same size from no contact to fifty`() {
        val sizes = listOf(0, 1, 2, 10, 25, 50).map { count -> bookOf(count = count).serialize().size }

        assertEquals(setOf(16 * 1024), sizes.toSet())
    }

    @Test
    fun `a book past a size seals to the next and tells no more`() {
        assertEquals(32 * 1024, bookOf(count = 60).serialize().size)
        assertEquals(32 * 1024, bookOf(count = 100).serialize().size)
        assertEquals(64 * 1024, bookOf(count = 120).serialize().size)
    }

    @Test
    fun `a book takes the first size it fits exactly`() {
        assertEquals(16 * 1024, bookTaking(size = 16 * 1024).serialize().size)
        assertEquals(32 * 1024, bookTaking(size = 16 * 1024 + 1).serialize().size)
        assertEquals(1024 * 1024, bookTaking(size = 1024 * 1024).serialize().size)
    }

    /** The next size would not fit what the server takes for the body of a write, 2 MiB once sealed. */
    @Test
    fun `a book past the largest size keeps its own length`() {
        val size = 1024 * 1024 + 1

        assertEquals(size, bookTaking(size = size).serialize().size)
    }

    @Test
    fun `a padded book reads back as it was written`() {
        val book = assertNotNull(
            ContactBook.EMPTY
                .with(alice, contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = "42"))
                ?.withFormerContact(bob, fullName = "Bob  Martin "),
        )

        val read = assertNotNull(ContactBook.parse(book.serialize()))

        assertContentEquals(aliceKey, read.contacts.getValue(alice).profileEncryptionKey)
        assertEquals("42", read.contacts.getValue(alice).verifiedSafetyNumber)
        assertEquals(mapOf(bob to "Bob  Martin "), read.formerContacts)
    }

    /**
     * Written before books were padded, or by a device that pads otherwise: refused, it would leave
     * the only copy of the user's contacts out of reach, and no contact could be added.
     */
    @Test
    fun `a book of any size reads and is padded the next time it is written`() {
        val unpadded = bookOf(count = 1).serialize().decodeToString().trimEnd().encodeToByteArray()

        val read = assertNotNull(ContactBook.parse(unpadded))

        assertEquals(1, read.contacts.size)
        assertEquals(16 * 1024, assertNotNull(read.with(alice, contact(profileEncryptionKey = aliceKey))).serialize().size)
    }

    @Test
    fun `an empty book holds no contact`() {
        assertTrue(ContactBook.EMPTY.contacts.isEmpty())
    }

    @Test
    fun `contacts survive being sealed and read back`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(setOf(alice, bob), reread.keys)
        assertContentEquals(aliceKey, reread[alice]?.profileEncryptionKey)
        assertContentEquals(bobKey, reread[bob]?.profileEncryptionKey)
    }

    @Test
    fun `adding a contact already in the book replaces their key`() {
        val replacement = byteArrayOf(9, 9)

        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = replacement))!!

        assertEquals(setOf(alice), book.contacts.keys)
        assertContentEquals(replacement, book.contacts[alice]?.profileEncryptionKey)
    }

    @Test
    fun `a removed contact is gone and the others stay`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
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
        val book = ContactBook.EMPTY.with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!

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
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
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
        )!!

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
            )!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = bobKey))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertContentEquals(bobKey, reread[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, reread[alice]?.mlsSignaturePublicKey)
    }

    /**
     * No flow in the app changes a pin, so a different one was most likely served by
     * somebody other than the contact, and writing it would hand it to every device that
     * restores.
     */
    @Test
    fun `writing a contact with another pinned identity is refused`() {
        val book = ContactBook.EMPTY.with(
            userUuid = alice,
            contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey),
        )!!

        val rewritten = book.with(
            userUuid = alice,
            contact = contact(profileEncryptionKey = bobKey, mlsSignaturePublicKey = byteArrayOf(8, 8, 8)),
        )

        assertNull(rewritten)
    }

    @Test
    fun `writing a contact with the identity already pinned replaces their key`() {
        val book = ContactBook.EMPTY
            .with(
                userUuid = alice,
                contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey),
            )!!
            .with(
                userUuid = alice,
                contact = contact(profileEncryptionKey = bobKey, mlsSignaturePublicKey = aliceSignatureKey.copyOf()),
            )!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertContentEquals(bobKey, reread[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, reread[alice]?.mlsSignaturePublicKey)
    }

    @Test
    fun `a contact stored without a pinned identity takes the one written`() {
        val plaintext = """{"$alice": {"profile_encryption_key": "AQID"}}""".encodeToByteArray()

        val book = ContactBook.parse(plaintext = plaintext)!!.with(
            userUuid = alice,
            contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey),
        )!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

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
    fun `which of the user's keys a contact was handed survives being sealed and read back`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, handedProfileKeyHash = handedNow))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey, handedProfileKeyHash = handedBefore))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(handedNow, reread[alice]?.handedProfileKeyHash)
        assertEquals(handedBefore, reread[bob]?.handedProfileKeyHash)
    }

    /** Handed a newer key since, which is what the contact holds from then on. */
    @Test
    fun `writing a contact with a handed hash replaces the one stored`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, handedProfileKeyHash = handedBefore))!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, handedProfileKeyHash = handedNow))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(handedNow, reread[alice]?.handedProfileKeyHash)
    }

    /** As with a pinned identity, not knowing which key they hold is no grounds for erasing it. */
    @Test
    fun `writing a contact without a handed hash keeps the one stored`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, handedProfileKeyHash = handedNow))!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = bobKey))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertContentEquals(bobKey, reread[alice]?.profileEncryptionKey)
        assertEquals(handedNow, reread[alice]?.handedProfileKeyHash)
    }

    @Test
    fun `a handed hash survives writing and removing other contacts`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, handedProfileKeyHash = handedNow))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .without(userUuid = bob)

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(handedNow, reread[alice]?.handedProfileKeyHash)
    }

    /** Nobody knows which of the user's keys they hold, so they are handed the current one. */
    @Test
    fun `a contact with no handed hash is still a contact`() {
        val plaintext = """{"$alice": {"profile_encryption_key": "AQID", "handed_profile_key_hash": 12}}"""
            .encodeToByteArray()

        val reread = ContactBook.parse(plaintext = plaintext)!!.contacts

        assertEquals(setOf(alice), reread.keys)
        assertNull(reread[alice]?.handedProfileKeyHash)
    }

    @Test
    fun `a verified safety number survives being sealed and read back`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = aliceSafetyNumber))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(aliceSafetyNumber, reread[alice]?.verifiedSafetyNumber)
        assertNull(reread[bob]?.verifiedSafetyNumber)
    }

    /**
     * Unlike a pin or a handed hash, none is the user clearing the mark: every device holding the
     * contact holds their mark too, restored along with them.
     */
    @Test
    fun `writing a contact without a verified safety number clears the one stored`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = aliceSafetyNumber))!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(setOf(alice), reread.keys)
        assertNull(reread[alice]?.verifiedSafetyNumber)
    }

    @Test
    fun `writing a contact with a verified safety number replaces the one stored`() {
        val compared = "11111 22222 33333 44444 55555 66666 77777 88888 99999 00000 12345 67890"

        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = aliceSafetyNumber))!!
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = compared))!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(compared, reread[alice]?.verifiedSafetyNumber)
    }

    @Test
    fun `a verified safety number survives writing and removing other contacts`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, verifiedSafetyNumber = aliceSafetyNumber))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .without(userUuid = bob)

        val reread = ContactBook.parse(plaintext = book.serialize())!!.contacts

        assertEquals(aliceSafetyNumber, reread[alice]?.verifiedSafetyNumber)
    }

    /** Only a newer build could have written something else there, and it vouches for nothing here. */
    @Test
    fun `a contact whose verified safety number is not text is not verified`() {
        val plaintext = """{"$alice": {"profile_encryption_key": "AQID", "verified_safety_number": 12}}"""
            .encodeToByteArray()

        val reread = ContactBook.parse(plaintext = plaintext)!!.contacts

        assertEquals(setOf(alice), reread.keys)
        assertNull(reread[alice]?.verifiedSafetyNumber)
    }

    @Test
    fun `an empty book holds no former contact`() {
        assertTrue(ContactBook.EMPTY.formerContacts.isEmpty())
    }

    /**
     * Alice is no longer a contact, whoever ended it: the key to her profile goes, so her name is
     * all there is left to keep, and her pin and the rest go with the contact she no longer is.
     */
    @Test
    fun `a former contact survives being sealed and read back as their name alone`() {
        val book = ContactBook.EMPTY
            .with(
                userUuid = alice,
                contact = contact(
                    profileEncryptionKey = aliceKey,
                    mlsSignaturePublicKey = aliceSignatureKey,
                    handedProfileKeyHash = handedNow,
                    verifiedSafetyNumber = aliceSafetyNumber,
                ),
            )!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withFormerContact(userUuid = alice, fullName = "Alice Martin")

        val reread = ContactBook.parse(plaintext = book.serialize())!!
        val entry = Json.parseToJsonElement(book.serialize().decodeToString()).jsonObject.getValue(alice)

        assertEquals(mapOf(alice to "Alice Martin"), reread.formerContacts)
        assertEquals(setOf(bob), reread.contacts.keys)
        assertEquals(setOf("former_contact", "full_name"), entry.jsonObject.keys)
    }

    /** Nothing else is left to keep of her, so she goes. */
    @Test
    fun `a former contact with no name known is dropped`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withFormerContact(userUuid = alice, fullName = null)

        val reread = ContactBook.parse(plaintext = book.serialize())!!

        assertTrue(reread.formerContacts.isEmpty())
        assertEquals(setOf(bob), reread.contacts.keys)
        assertTrue(!book.serialize().decodeToString().contains(alice), "the former contact is still there")
    }

    /** Marked as a former contact, an entry is no contact's whatever else it carries. */
    @Test
    fun `the entry of a former contact is not a contact even with a key`() {
        val plaintext = """
            {
                "$alice": {"former_contact": true, "full_name": "Alice Martin", "profile_encryption_key": "AQID"},
                "$bob": {"profile_encryption_key": "BAUG"}
            }
        """.trimIndent().encodeToByteArray()

        val book = ContactBook.parse(plaintext = plaintext)!!

        assertEquals(setOf(bob), book.contacts.keys)
        assertEquals(mapOf(alice to "Alice Martin"), book.formerContacts)
    }

    /** Only the entry of the former contact is replaced. */
    @Test
    fun `what this build does not know survives turning a contact into a former one`() {
        val plaintext = """
            {
                "$alice": {"profile_encryption_key": "AQID", "nickname": "Al"},
                "$bob": {"profile_encryption_key": "BAUG", "nickname": "Bobby"},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val rewritten = ContactBook.parse(plaintext = plaintext)!!
            .withFormerContact(userUuid = alice, fullName = "Alice Martin")
            .serialize()
            .decodeToString()

        assertTrue(rewritten.contains("\"nickname\":\"Bobby\""), "the unknown field was dropped: $rewritten")
        assertTrue(
            rewritten.contains("\"a_newer_kind_of_entry\":[1,2]"),
            "the unknown entry was dropped: $rewritten",
        )
        assertTrue(!rewritten.contains("\"nickname\":\"Al\""), "the former contact kept a field: $rewritten")
    }

    @Test
    fun `a former contact survives writing and removing other contacts`() {
        val book = ContactBook.EMPTY
            .withFormerContact(userUuid = alice, fullName = "Alice Martin")
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .without(userUuid = bob)

        val reread = ContactBook.parse(plaintext = book.serialize())!!

        assertEquals(mapOf(alice to "Alice Martin"), reread.formerContacts)
        assertTrue(reread.contacts.isEmpty())
    }

    /**
     * A new request made Alice and the user contacts again, accepted either way. Kept, the marker
     * would leave her out of the contacts, and every restore would bring her back as a name alone,
     * with no pin.
     */
    @Test
    fun `a former contact added back is a contact again with nothing of the former one left`() {
        val book = ContactBook.EMPTY
            .withFormerContact(userUuid = alice, fullName = "Alice Martin")
            .with(
                userUuid = alice,
                contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey),
            )!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!
        val entry = Json.parseToJsonElement(book.serialize().decodeToString()).jsonObject.getValue(alice)

        assertEquals(setOf(alice), reread.contacts.keys)
        assertContentEquals(aliceKey, reread.contacts[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, reread.contacts[alice]?.mlsSignaturePublicKey)
        assertTrue(reread.formerContacts.isEmpty())
        assertEquals(setOf("profile_encryption_key", "mls_signature_public_key"), entry.jsonObject.keys)
    }

    /**
     * What a backup writes once Alice removed the user, Bob deleted his account and the user
     * removed Carol, whom this device never read a name for, while Dave was added: of the first
     * two, their names alone are left, the same way, and nothing at all of Carol.
     */
    @Test
    fun `a backup keeps nothing of a former contact but their name whoever ended it`() {
        val carol = "f0b7a2a1-5a9c-4f0e-9a43-0c1d2e3f4a5b"
        val dave = "5b4a3f2e-1d0c-43a9-8e0f-9c5a1a2b7f0a"

        val update = ContactBook.EMPTY
            .with(
                userUuid = alice,
                contact = contact(
                    profileEncryptionKey = aliceKey,
                    mlsSignaturePublicKey = aliceSignatureKey,
                    handedProfileKeyHash = handedNow,
                ),
            )!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .with(userUuid = carol, contact = contact(profileEncryptionKey = bobKey))!!
            .withChanges(
                added = mapOf(dave to contact(profileEncryptionKey = aliceKey)),
                removed = mapOf(alice to "Alice Martin", bob to "Bob Durand", carol to null),
            )

        val serialized = update.book.serialize()
        val reread = ContactBook.parse(plaintext = serialized)!!
        val entries = Json.parseToJsonElement(serialized.decodeToString()).jsonObject

        assertEquals(mapOf(alice to "Alice Martin", bob to "Bob Durand"), reread.formerContacts)
        assertEquals(setOf(dave), reread.contacts.keys)
        assertEquals(setOf("former_contact", "full_name"), entries.getValue(alice).jsonObject.keys)
        assertEquals(setOf("former_contact", "full_name"), entries.getValue(bob).jsonObject.keys)
        assertTrue(carol !in entries.keys, "the former contact with no name is still there")
        assertTrue(update.conflicting.isEmpty())
    }

    @Test
    fun `a backup leaves a contact pinned as somebody else as it was`() {
        val update = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withChanges(
                added = mapOf(
                    alice to contact(profileEncryptionKey = byteArrayOf(9, 9), mlsSignaturePublicKey = byteArrayOf(8, 8, 8)),
                ),
                removed = mapOf(bob to "Bob Durand"),
            )

        assertEquals(setOf(alice), update.conflicting)
        assertEquals(setOf(alice), update.book.contacts.keys)
        assertContentEquals(aliceKey, update.book.contacts[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, update.book.contacts[alice]?.mlsSignaturePublicKey)
        assertEquals(mapOf(bob to "Bob Durand"), update.book.formerContacts)
    }

    @Test
    fun `plaintext that is not a JSON object does not parse`() {
        assertNull(ContactBook.parse(plaintext = "not a contact book".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = byteArrayOf()))
    }
}
