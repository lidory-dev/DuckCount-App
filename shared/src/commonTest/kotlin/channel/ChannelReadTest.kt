package channel

import database.data.local.entity.DirectChannelEntity
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ALICE = "55555555-5555-5555-5555-555555555555"

/** The group the user's contact request to Alice created, which her answer arrives in. */
private val REQUEST_GROUP = ByteArray(32) { 1 }

private val CHANNEL_GROUP = ByteArray(32) { 2 }
private val PREVIOUS_GROUP = ByteArray(32) { 3 }
private val OLDER_GROUP = ByteArray(32) { 4 }

/** What a message read in one group makes of the channel to its sender, see [readIn]. */
class ChannelReadTest {

    private val channel = channel(current = CHANNEL_GROUP)

    /** Pinned since the request went out, so what she sends is read whether she answers or not. */
    @Test
    fun `the recipient of an unanswered request read in its group gets no channel`() {
        assertEquals(ChannelRead.UNCHANGED, readIn(channel = null, mlsGroupId = REQUEST_GROUP, isContact = false))
    }

    /** A contact removed since, say, who keeps their channel and still owes the user a key. */
    @Test
    fun `somebody who is not a contact read in a request's group leaves their channel where it is`() {
        assertEquals(ChannelRead.UNCHANGED, readIn(channel = channel, mlsGroupId = REQUEST_GROUP, isContact = false))
    }

    /** As `ContactRequestRepository.receiveAnswer` leaves it: the request's group is the channel. */
    @Test
    fun `once she answered she is read in the channel`() {
        val answered = channel(current = REQUEST_GROUP)

        assertEquals(ChannelRead.JOINED, readIn(channel = answered, mlsGroupId = REQUEST_GROUP, isContact = true))
    }

    /** Two requests crossed, and each side adopted the group of a different one. */
    @Test
    fun `a contact read in a group the channel does not name moves the channel there`() {
        assertEquals(ChannelRead.MOVED, readIn(channel = channel, mlsGroupId = REQUEST_GROUP, isContact = true))
        assertEquals(ChannelRead.MOVED, readIn(channel = null, mlsGroupId = REQUEST_GROUP, isContact = true))
    }

    @Test
    fun `read in the channel's group the peer has joined it whether a contact or not`() {
        listOf(true, false).forEach { isContact ->
            assertEquals(ChannelRead.JOINED, readIn(channel = channel, mlsGroupId = CHANNEL_GROUP, isContact = isContact))
        }
    }

    @Test
    fun `read in the group the channel replaced nothing changes`() {
        listOf(true, false).forEach { isContact ->
            assertEquals(ChannelRead.UNCHANGED, readIn(channel = channel, mlsGroupId = PREVIOUS_GROUP, isContact = isContact))
        }
    }

    /** It is one of the channel's own, about to be pushed out while the peer still sends there. */
    @Test
    fun `read in the oldest group kept the channel moves back there whether a contact or not`() {
        listOf(true, false).forEach { isContact ->
            assertEquals(ChannelRead.MOVED, readIn(channel = channel, mlsGroupId = OLDER_GROUP, isContact = isContact))
        }
    }

    private fun channel(current: ByteArray) = DirectChannelEntity(
        peerUuid = ALICE,
        mlsGroupId = current,
        previousMlsGroupId = PREVIOUS_GROUP,
        olderMlsGroupId = OLDER_GROUP,
        pendingWelcome = null,
        pendingWelcomeSince = null,
        replyBudgetFullAt = null,
    )
}
