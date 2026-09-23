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
     * this device currently believes the channel to be.
     */
    suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling
}

/** What a handler made of a message. */
internal enum class DirectMessageHandling {

    /** It was this handler's, and it is done with. The message is acknowledged. */
    HANDLED,

    /** Not this handler's kind of message. The next one is offered it. */
    NOT_MINE,

    /**
     * It was this handler's, but the device could not store what it said.
     *
     * The message is left unacknowledged so the server hands it back, which is the only
     * way to get it again: reading an MLS message consumes the key that opened it, so this
     * has to mean nothing was consumed.
     */
    RETRY,
}
