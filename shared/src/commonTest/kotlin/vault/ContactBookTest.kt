package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun `an empty book holds no deleted account`() {
        assertTrue(ContactBook.EMPTY.deletedAccounts.isEmpty())
    }

    /**
     * Alice deleted her account: her profile went with it, so her name is all there is left to
     * keep, and her key, her pin and the rest go with the contact she no longer is.
     */
    @Test
    fun `a contact who deleted their account survives being sealed and read back as their name alone`() {
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
            .withDeletedAccount(userUuid = alice, fullName = "Alice Martin")

        val reread = ContactBook.parse(plaintext = book.serialize())!!
        val entry = Json.parseToJsonElement(book.serialize().decodeToString()).jsonObject.getValue(alice)

        assertEquals(mapOf(alice to "Alice Martin"), reread.deletedAccounts)
        assertEquals(setOf(bob), reread.contacts.keys)
        assertEquals(setOf("account_deleted", "full_name"), entry.jsonObject.keys)
    }

    /** Nothing else is left to keep of her, so she goes as a removed contact does. */
    @Test
    fun `a contact who deleted their account with no name known is dropped`() {
        val book = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withDeletedAccount(userUuid = alice, fullName = null)

        val reread = ContactBook.parse(plaintext = book.serialize())!!

        assertTrue(reread.deletedAccounts.isEmpty())
        assertEquals(setOf(bob), reread.contacts.keys)
        assertTrue(!book.serialize().decodeToString().contains(alice), "the deleted account is still there")
    }

    /** Marked as an account deleted, an entry is no contact's whatever else it carries. */
    @Test
    fun `the entry of a deleted account is not a contact even with a key`() {
        val plaintext = """
            {
                "$alice": {"account_deleted": true, "full_name": "Alice Martin", "profile_encryption_key": "AQID"},
                "$bob": {"profile_encryption_key": "BAUG"}
            }
        """.trimIndent().encodeToByteArray()

        val book = ContactBook.parse(plaintext = plaintext)!!

        assertEquals(setOf(bob), book.contacts.keys)
        assertEquals(mapOf(alice to "Alice Martin"), book.deletedAccounts)
    }

    /** Only the entry of the contact who deleted their account is replaced. */
    @Test
    fun `what this build does not know survives turning a contact into a deleted account`() {
        val plaintext = """
            {
                "$alice": {"profile_encryption_key": "AQID", "nickname": "Al"},
                "$bob": {"profile_encryption_key": "BAUG", "nickname": "Bobby"},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val rewritten = ContactBook.parse(plaintext = plaintext)!!
            .withDeletedAccount(userUuid = alice, fullName = "Alice Martin")
            .serialize()
            .decodeToString()

        assertTrue(rewritten.contains("\"nickname\":\"Bobby\""), "the unknown field was dropped: $rewritten")
        assertTrue(
            rewritten.contains("\"a_newer_kind_of_entry\":[1,2]"),
            "the unknown entry was dropped: $rewritten",
        )
        assertTrue(!rewritten.contains("\"nickname\":\"Al\""), "the deleted account kept a field: $rewritten")
    }

    @Test
    fun `a deleted account survives writing and removing other contacts`() {
        val book = ContactBook.EMPTY
            .withDeletedAccount(userUuid = alice, fullName = "Alice Martin")
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .without(userUuid = bob)

        val reread = ContactBook.parse(plaintext = book.serialize())!!

        assertEquals(mapOf(alice to "Alice Martin"), reread.deletedAccounts)
        assertTrue(reread.contacts.isEmpty())
    }

    /**
     * Alice's deletion failed once its notice was out, and she and the user are contacts again.
     * Kept, the mark would have every restore bring her back as a name alone, with no pin.
     */
    @Test
    fun `a contact added back over the entry of a deleted account is a contact again`() {
        val book = ContactBook.EMPTY
            .withDeletedAccount(userUuid = alice, fullName = "Alice Martin")
            .with(
                userUuid = alice,
                contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey),
            )!!

        val reread = ContactBook.parse(plaintext = book.serialize())!!
        val entry = Json.parseToJsonElement(book.serialize().decodeToString()).jsonObject.getValue(alice)

        assertEquals(setOf(alice), reread.contacts.keys)
        assertContentEquals(aliceKey, reread.contacts[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, reread.contacts[alice]?.mlsSignaturePublicKey)
        assertTrue(reread.deletedAccounts.isEmpty())
        assertEquals(setOf("profile_encryption_key", "mls_signature_public_key"), entry.jsonObject.keys)
    }

    /**
     * What a backup writes once Alice deleted her account and Bob was added: of her, her name
     * alone is left.
     */
    @Test
    fun `a backup keeps nothing of a contact who deleted their account but their name`() {
        val update = ContactBook.EMPTY
            .with(
                userUuid = alice,
                contact = contact(
                    profileEncryptionKey = aliceKey,
                    mlsSignaturePublicKey = aliceSignatureKey,
                    handedProfileKeyHash = handedNow,
                ),
            )!!
            .withChanges(
                added = mapOf(bob to contact(profileEncryptionKey = bobKey)),
                removed = emptyList(),
                deletedAccounts = mapOf(alice to "Alice Martin"),
            )

        val reread = ContactBook.parse(plaintext = update.book.serialize())!!
        val entry = Json.parseToJsonElement(update.book.serialize().decodeToString()).jsonObject.getValue(alice)

        assertEquals(mapOf(alice to "Alice Martin"), reread.deletedAccounts)
        assertEquals(setOf(bob), reread.contacts.keys)
        assertEquals(setOf("account_deleted", "full_name"), entry.jsonObject.keys)
        assertTrue(update.conflicting.isEmpty())
    }

    /** This device never read Alice's profile, and nothing else is left to keep of her. */
    @Test
    fun `a backup drops a contact who deleted their account with no name known`() {
        val update = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withChanges(added = emptyMap(), removed = emptyList(), deletedAccounts = mapOf(alice to null))

        assertTrue(update.book.deletedAccounts.isEmpty())
        assertEquals(setOf(bob), update.book.contacts.keys)
        assertTrue(!update.book.serialize().decodeToString().contains(alice), "the deleted account is still there")
    }

    @Test
    fun `a backup drops the contacts removed and leaves one pinned as somebody else as it was`() {
        val update = ContactBook.EMPTY
            .with(userUuid = alice, contact = contact(profileEncryptionKey = aliceKey, mlsSignaturePublicKey = aliceSignatureKey))!!
            .with(userUuid = bob, contact = contact(profileEncryptionKey = bobKey))!!
            .withChanges(
                added = mapOf(
                    alice to contact(profileEncryptionKey = byteArrayOf(9, 9), mlsSignaturePublicKey = byteArrayOf(8, 8, 8)),
                ),
                removed = listOf(bob),
                deletedAccounts = emptyMap(),
            )

        assertEquals(setOf(alice), update.conflicting)
        assertEquals(setOf(alice), update.book.contacts.keys)
        assertContentEquals(aliceKey, update.book.contacts[alice]?.profileEncryptionKey)
        assertContentEquals(aliceSignatureKey, update.book.contacts[alice]?.mlsSignaturePublicKey)
    }

    @Test
    fun `plaintext that is not a JSON object does not parse`() {
        assertNull(ContactBook.parse(plaintext = "not a contact book".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(ContactBook.parse(plaintext = byteArrayOf()))
    }
}
