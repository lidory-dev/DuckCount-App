package mls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the cleartext header of an MLS message tells of it, as the server reads it, see
 * [commitEpochOf]: the epoch a Commit was built in, and of anything else nothing.
 */
class CommitEpochTest {

    @Test
    fun `a Commit reads as the epoch it was built in`() {
        assertEquals(7L, commitEpochOf(message(epoch = 7, contentType = 3)))
    }

    /** A group id written in two bytes, as one longer than 63 bytes is. */
    @Test
    fun `a Commit whose group id is longer reads all the same`() {
        assertEquals(
            300L,
            commitEpochOf(message(epoch = 300, contentType = 3, groupIdLength = byteArrayOf(0x40, 0x50), groupId = 0x50)),
        )
    }

    @Test
    fun `an application message or a proposal is no Commit`() {
        assertNull(commitEpochOf(message(epoch = 7, contentType = 1)))
        assertNull(commitEpochOf(message(epoch = 7, contentType = 2)))
    }

    /** A Welcome, a GroupInfo, a member's PublicMessage, or another version of MLS. */
    @Test
    fun `a message of another wire format or version is no Commit`() {
        assertNull(commitEpochOf(message(epoch = 7, contentType = 3, wireFormat = 3)))
        assertNull(commitEpochOf(message(epoch = 7, contentType = 3, wireFormat = 4)))
        assertNull(commitEpochOf(message(epoch = 7, contentType = 3, wireFormat = 1)))
        assertNull(commitEpochOf(message(epoch = 7, contentType = 3, version = 2)))
    }

    /** The one Commit in the clear, with which a member rejoins a group after a log-out. */
    @Test
    fun `a Commit from outside the group reads as the epoch it was built in`() {
        assertEquals(7L, commitEpochOf(publicMessage(epoch = 7, senderType = 4, contentType = 3)))
    }

    /** Authenticated data written in two bytes, as more than 63 bytes of it are. */
    @Test
    fun `a Commit from outside the group with authenticated data reads all the same`() {
        val authenticatedData = byteArrayOf(0x40, 0x41) + ByteArray(65)

        assertEquals(
            9L,
            commitEpochOf(publicMessage(epoch = 9, senderType = 4, contentType = 3, authenticatedData = authenticatedData)),
        )
    }

    /**
     * A member's, an external sender's, or a would-be member's Proposal or application message:
     * none of which the server stores as a Commit.
     */
    @Test
    fun `a PublicMessage from anybody but a member rejoining is no Commit`() {
        assertNull(commitEpochOf(publicMessage(epoch = 7, senderType = 1, contentType = 3)))
        assertNull(commitEpochOf(publicMessage(epoch = 7, senderType = 2, contentType = 3)))
        assertNull(commitEpochOf(publicMessage(epoch = 7, senderType = 3, contentType = 2)))
        assertNull(commitEpochOf(publicMessage(epoch = 7, senderType = 4, contentType = 2)))
    }

    @Test
    fun `a PublicMessage too short to hold the header is no Commit`() {
        val commit = publicMessage(epoch = 7, senderType = 4, contentType = 3)

        assertNull(commitEpochOf(commit.copyOf(commit.size - "signed".length - 1)))
    }

    @Test
    fun `a message too short to hold the header is no Commit`() {
        val commit = message(epoch = 7, contentType = 3)

        assertNull(commitEpochOf(commit.copyOf(commit.size - "sealed".length - 1)))
        assertNull(commitEpochOf(ByteArray(0)))
    }

    /**
     * The header of a `PublicMessage`: its version and wire format, the group id's length and the id,
     * the epoch, the sender, its type and the leaf index a member or an external sender carries, the
     * authenticated data, its length first, and the content type, then what would be signed.
     */
    private fun publicMessage(
        epoch: Long,
        senderType: Int,
        contentType: Int,
        authenticatedData: ByteArray = byteArrayOf(0),
    ): ByteArray =
        byteArrayOf(0, 1, 0, 1, 32) +
            ByteArray(32) { 7 } +
            ByteArray(8) { index -> (epoch shr (8 * (7 - index))).toByte() } +
            byteArrayOf(senderType.toByte()) +
            (if (senderType == 1 || senderType == 2) byteArrayOf(0, 0, 0, 2) else ByteArray(0)) +
            authenticatedData +
            byteArrayOf(contentType.toByte()) +
            "signed".encodeToByteArray()

    /**
     * The header of an MLS message: its version, wire format, the group id's length and the id, the
     * epoch and the content type, then what would be sealed.
     */
    private fun message(
        epoch: Long,
        contentType: Int,
        version: Int = 1,
        wireFormat: Int = 2,
        groupIdLength: ByteArray = byteArrayOf(32),
        groupId: Int = 32,
    ): ByteArray =
        byteArrayOf(0, version.toByte(), 0, wireFormat.toByte()) +
            groupIdLength +
            ByteArray(groupId) { 7 } +
            ByteArray(8) { index -> (epoch shr (8 * (7 - index))).toByte() } +
            byteArrayOf(contentType.toByte()) +
            "sealed".encodeToByteArray()
}
