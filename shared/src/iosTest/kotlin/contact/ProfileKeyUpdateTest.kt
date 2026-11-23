package contact

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val KEY = ByteArray(32) { 7 }

/** When the sender last edited their profile, in milliseconds since the epoch. */
private const val EDITED_AT = 1_763_900_000_000L

/**
 * What a [ProfileKeyUpdate] writes and reads, the time of an edit it tells of included, see
 * [ProfileKeyUpdate.profileRevision]: written as before when it tells of none, which is every
 * update to a contact, and read alike from a build that writes none.
 */
class ProfileKeyUpdateTest {

    @Test
    fun `an update telling of no edit is written as before`() {
        val written = ProfileKeyUpdate(profileEncryptionKey = KEY).serialize().decodeToString()

        assertFalse("profile_revision" in written)
    }

    @Test
    fun `an update telling of an edit reads back with it`() {
        val read = assertNotNull(
            ProfileKeyUpdate.parse(ProfileKeyUpdate(profileEncryptionKey = KEY, profileRevision = EDITED_AT).serialize()),
        )

        assertContentEquals(KEY, read.profileEncryptionKey)
        assertEquals(EDITED_AT, read.profileRevision)
    }

    /** As a build from before writes it. */
    @Test
    fun `an update with no revision reads as telling of no edit`() {
        val written = """{"type":"profile_key","profile_encryption_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="}"""

        val read = assertNotNull(ProfileKeyUpdate.parse(written.encodeToByteArray()))

        assertContentEquals(KEY, read.profileEncryptionKey)
        assertNull(read.profileRevision)
    }
}
