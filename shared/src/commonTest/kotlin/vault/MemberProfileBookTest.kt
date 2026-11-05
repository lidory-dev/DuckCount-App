package vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemberProfileBookTest {

    private val nina = "136f99cf-f4dd-4b2e-a03d-6c42f47343f6"
    private val paul = "003c8dd5-1f21-44ac-8090-afdaeb04cd66"

    private val ninaKey = byteArrayOf(1, 2, 3)
    private val ninaNewKey = byteArrayOf(7, 8, 9)
    private val paulKey = byteArrayOf(4, 5, 6)

    private fun member(profileEncryptionKey: ByteArray, isFromOwner: Boolean, fullName: String? = null) =
        VaultMemberProfile(profileEncryptionKey = profileEncryptionKey, isFromOwner = isFromOwner, fullName = fullName)

    /** A book of [count] members with as long a name as a plain one gets, each with their own UUID. */
    private fun bookOf(count: Int): MemberProfileBook =
        (0 until count).fold(MemberProfileBook.EMPTY) { book, index ->
            book.with(
                userUuid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                member = VaultMemberProfile(
                    profileEncryptionKey = ByteArray(32),
                    isFromOwner = true,
                    fullName = "a".repeat(50),
                ),
            )
        }

    /** Its length would otherwise say how many people the user shares a group with, to the person. */
    @Test
    fun `a book seals to the same size from no member to eighty`() {
        val sizes = listOf(0, 1, 2, 10, 40, 80).map { count -> bookOf(count = count).serialize().size }

        assertEquals(setOf(16 * 1024), sizes.toSet())
    }

    @Test
    fun `a book past a size seals to the next and tells no more`() {
        assertEquals(32 * 1024, bookOf(count = 90).serialize().size)
        assertEquals(32 * 1024, bookOf(count = 160).serialize().size)
        assertEquals(64 * 1024, bookOf(count = 200).serialize().size)
    }

    @Test
    fun `a padded book reads back as it was written`() {
        val book = MemberProfileBook.EMPTY.with(
            userUuid = "00000000-0000-4000-8000-000000000000",
            member = VaultMemberProfile(
                profileEncryptionKey = byteArrayOf(1, 2, 3),
                isFromOwner = true,
                fullName = "Ada  Lovelace ",
            ),
        )

        val member = assertNotNull(MemberProfileBook.parse(book.serialize())).members.values.single()

        assertContentEquals(byteArrayOf(1, 2, 3), member.profileEncryptionKey)
        assertTrue(member.isFromOwner)
        assertEquals("Ada  Lovelace ", member.fullName)
    }

    /**
     * Written before books were padded, or by a device that pads otherwise: refused, it would leave
     * names out of reach that may be nowhere else any more, and no member could be written.
     */
    @Test
    fun `a book of any size reads and is padded the next time it is written`() {
        val unpadded = bookOf(count = 1).serialize().decodeToString().trimEnd().encodeToByteArray()

        val read = assertNotNull(MemberProfileBook.parse(unpadded))

        assertEquals(1, read.members.size)

        val written = read.with(
            userUuid = "00000000-0000-4000-8000-000000000001",
            member = VaultMemberProfile(profileEncryptionKey = ByteArray(32), isFromOwner = false, fullName = null),
        )

        assertEquals(16 * 1024, written.serialize().size)
    }

    @Test
    fun `an empty book holds no member`() {
        assertTrue(MemberProfileBook.EMPTY.members.isEmpty())
    }

    @Test
    fun `members survive being sealed and read back`() {
        val book = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = true, fullName = "Nina Petit"))
            .with(userUuid = paul, member = member(paulKey, isFromOwner = false))

        val reread = MemberProfileBook.parse(plaintext = book.serialize())!!.members

        assertEquals(setOf(nina, paul), reread.keys)
        assertContentEquals(ninaKey, reread.getValue(nina).profileEncryptionKey)
        assertTrue(reread.getValue(nina).isFromOwner)
        assertEquals("Nina Petit", reread.getValue(nina).fullName)
        assertContentEquals(paulKey, reread.getValue(paul).profileEncryptionKey)
        assertFalse(reread.getValue(paul).isFromOwner)
        assertNull(reread.getValue(paul).fullName)
    }

    /** The creator may hold a key its owner replaced since. */
    @Test
    fun `a relayed key never replaces one from its owner`() {
        val book = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true, fullName = "Nina Petit"))
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = false, fullName = "Nina Leroy"))

        val held = book.members.getValue(nina)

        assertContentEquals(ninaNewKey, held.profileEncryptionKey)
        assertTrue(held.isFromOwner)
        assertEquals("Nina Petit", held.fullName)
    }

    @Test
    fun `a key from its owner replaces a relayed one`() {
        val book = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = false, fullName = "Nina Leroy"))
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true, fullName = "Nina Petit"))

        val held = book.members.getValue(nina)

        assertContentEquals(ninaNewKey, held.profileEncryptionKey)
        assertTrue(held.isFromOwner)
        assertEquals("Nina Petit", held.fullName)
    }

    /** This device's word is the later one where both have it from the same kind of source. */
    @Test
    fun `a key from as good a source replaces the one held`() {
        val fromOwners = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = true))
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true))

        val relayed = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = false))
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = false))

        assertContentEquals(ninaNewKey, fromOwners.members.getValue(nina).profileEncryptionKey)
        assertContentEquals(ninaNewKey, relayed.members.getValue(nina).profileEncryptionKey)
    }

    /** A device that read no name knows nothing about it. */
    @Test
    fun `a member written with no name keeps the one the book held`() {
        val book = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaKey, isFromOwner = false, fullName = "Nina Leroy"))
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true))

        assertEquals("Nina Leroy", book.members.getValue(nina).fullName)
    }

    @Test
    fun `changes write every member they carry`() {
        val book = MemberProfileBook.EMPTY
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true))
            .withChanges(
                added = mapOf(
                    nina to member(ninaKey, isFromOwner = false),
                    paul to member(paulKey, isFromOwner = false, fullName = "Paul Roux"),
                ),
            )

        assertEquals(setOf(nina, paul), book.members.keys)
        assertContentEquals(ninaNewKey, book.members.getValue(nina).profileEncryptionKey)
        assertEquals("Paul Roux", book.members.getValue(paul).fullName)
    }

    /**
     * Same rule as the key ring's: whatever a newer app version stored must come back out of an
     * older version's write, or writing a member would quietly destroy it.
     */
    @Test
    fun `what this build does not know survives writing a member`() {
        val plaintext = """
            {
                "$nina": {"profile_encryption_key": "AQID", "from_owner": false, "avatar": "x"},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val rewritten = MemberProfileBook.parse(plaintext = plaintext)!!
            .with(userUuid = nina, member = member(ninaNewKey, isFromOwner = true))
            .with(userUuid = paul, member = member(paulKey, isFromOwner = false))
            .serialize()
            .decodeToString()

        assertTrue(rewritten.contains("\"avatar\":\"x\""), "the unknown field was dropped: $rewritten")
        assertTrue(
            rewritten.contains("\"a_newer_kind_of_entry\":[1,2]"),
            "the unknown entry was dropped: $rewritten",
        )
    }

    @Test
    fun `an entry without a readable key is no member`() {
        val plaintext = """
            {
                "$nina": {"profile_encryption_key": "AQID"},
                "$paul": {"profile_encryption_key": {"nested": "value"}},
                "a_newer_kind_of_entry": [1, 2]
            }
        """.trimIndent().encodeToByteArray()

        val members = MemberProfileBook.parse(plaintext = plaintext)!!.members

        assertEquals(setOf(nina), members.keys)
    }

    /** The weaker of the two, which never stands in the way of a key from its owner. */
    @Test
    fun `an entry with no owner flag holds a relayed key`() {
        val plaintext = """{"$nina": {"profile_encryption_key": "AQID", "full_name": 12}}""".encodeToByteArray()

        val held = MemberProfileBook.parse(plaintext = plaintext)!!.members.getValue(nina)

        assertFalse(held.isFromOwner)
        assertNull(held.fullName)
    }

    @Test
    fun `bytes that are not a JSON object are no book`() {
        assertNull(MemberProfileBook.parse(plaintext = "[1, 2]".encodeToByteArray()))
        assertNull(MemberProfileBook.parse(plaintext = byteArrayOf(0x7b)))
    }
}
