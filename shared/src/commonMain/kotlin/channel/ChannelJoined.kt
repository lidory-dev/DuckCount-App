package channel

/**
 * What the side that joined a channel's group from a Welcome sends back in it, so the side
 * that built the group hears they are in and stops attaching the Welcome to its messages.
 *
 * Without it, a peer who joined and has nothing to say looks exactly like one who could not
 * join, and the Welcome runs out, see [DirectChannelRepository.ensureChannel]: a new group,
 * another of their key packages spent, and one more group in each side's window, pushing
 * the oldest out.
 *
 * Nothing handles it: being read in the group is all it is for, see
 * [DirectChannelRepository.noteReadIn]. Sent once per sync pass for the newest group joined
 * from a peer, and only while nothing else this device sent them because of what they sent
 * is still on their budget of replies, which keeps it to once an hour at most, see
 * [DirectChannelRepository.claimReply]. Lost or withheld, the Welcome stays attached until
 * this device sends anything else, or runs out as it would without it.
 */
internal object ChannelJoined {

    /** The plaintext to encrypt for the peer: a `type` no handler acts on. */
    fun serialize(): ByteArray = """{"type":"channel_joined"}""".encodeToByteArray()
}
