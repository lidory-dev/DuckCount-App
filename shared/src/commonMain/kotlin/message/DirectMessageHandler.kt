package message

/**
 * Something that acts on the direct messages of one kind.
 *
 * A direct message is whatever two users have to tell each other that the server must not
 * learn, and what it is is only knowable once it has been decrypted. So the sync decrypts
 * once and offers the plaintext to each handler in turn until one claims it.
 */
internal interface DirectMessageHandler {

    /**
     * Acts on [payload], which MLS authenticated as coming from [senderUserUuid] in the
     * group [mlsGroupId].
     *
     * [senderUserUuid] is the MLS sender, not the one the server attached to the message:
     * those agree or the message is never offered here. [mlsGroupId] is the group it was
     * actually read in, so a handler that answers can answer there rather than wherever
     * this device currently records as the channel.
     *
     * The message is consumed by then: reading it spent the key that opened it, so whatever
     * a handler fails to store of what it said is lost, see [DirectMessageSync].
     */
    suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling

    /**
     * Acts on what is better done once a pass than once a message, now that the pass has
     * offered every message it read: answering a peer who asked several times in it, see
     * [expense.OneTimeExpenseMessageHandler] and [contact.ContactMessageHandler].
     *
     * Called whether the pass got to its end or not, since the messages it read are spent
     * either way, and only ever by the sync, one pass at a time, see [DirectMessageSync].
     */
    suspend fun finishPass() {}
}

/** What a handler made of a message. */
internal enum class DirectMessageHandling {

    /** It was this handler's, and it is done with. The message is acknowledged. */
    HANDLED,

    /** Not this handler's kind of message. The next one is offered it. */
    NOT_MINE,
}
