package contact

import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import message.DirectMessageHandler
import message.DirectMessageHandling

/**
 * The direct messages that are about the contact relationship itself: a card accepting a
 * request the user sent, a notice that a contact removed the user, a contact's new key to
 * their profile, and a contact asking for the user's.
 *
 * A card is only an answer in the group the request created, see
 * [ContactRequestRepository.receiveAnswer], an update only counts from a contact, see
 * [ContactRepository.receiveProfileKey], and only a contact's request is answered, see
 * [ProfileKeyPull.answer]. Each is claimed whatever it turns out to be, so nothing else acts
 * on it.
 *
 * A card carries no `type`, it predates there being more than one kind of direct message,
 * so it is recognised by its shape. Everything added since says what it is.
 */
internal class ContactMessageHandler(
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val profileKeyPull: ProfileKeyPull,
) : DirectMessageHandler {

    /**
     * Who asked for the user's profile key in the pass under way, each with the group of their
     * latest request, answered once it is over, see [finishPass].
     */
    private val profileKeyRequests = LinkedHashMap<String, ByteArray>()

    override suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling {
        when (val message = ContactMessage.read(payload = payload)) {
            is ContactMessage.ProfileKey -> contactRepository.receiveProfileKey(
                senderUserUuid = senderUserUuid,
                profileEncryptionKey = message.update.profileEncryptionKey,
            )

            ContactMessage.KeyRequest -> profileKeyRequests[senderUserUuid] = mlsGroupId

            is ContactMessage.Answer -> contactRequestRepository.receiveAnswer(
                senderUserUuid = senderUserUuid,
                mlsGroupId = mlsGroupId,
                card = message.card,
            )

            ContactMessage.Removal -> contactRepository.receiveRemoval(senderUserUuid = senderUserUuid)

            null -> return DirectMessageHandling.NOT_MINE
        }

        return DirectMessageHandling.HANDLED
    }

    /**
     * Answers each contact who asked for the user's profile key in the pass once, in the group of
     * their latest request, for the reason the keys to expenses are, see
     * [KeyRequestsInPass][expense.KeyRequestsInPass]: requests pile up while this device is away,
     * and answered one by one, the copies would each spend a reply of the asker's budget, the
     * ones in a group they lost first.
     */
    override suspend fun finishPass() {
        val asked = profileKeyRequests.toList()

        profileKeyRequests.clear()

        asked.forEach { (requesterUuid, mlsGroupId) ->
            profileKeyPull.answer(requesterUuid = requesterUuid, mlsGroupId = mlsGroupId)
        }
    }
}

/** A direct message about the contact relationship, as [ContactMessageHandler] reads one. */
internal sealed interface ContactMessage {

    /** A [ContactCard], answering a request the user sent if it comes from its recipient. */
    class Answer(val card: ContactCard) : ContactMessage

    /** A [ContactRemoval]. */
    data object Removal : ContactMessage

    /** A [ProfileKeyUpdate]. */
    class ProfileKey(val update: ProfileKeyUpdate) : ContactMessage

    /** A [ProfileKeyRequest]. */
    data object KeyRequest : ContactMessage

    companion object {
        /**
         * What [payload] is, or null when it is none of these.
         *
         * An update and a request are tried before a card: a card is recognised by carrying a
         * `profile_encryption_key`, its unknown keys ignored, so anything that carries one, as
         * an update does, would be taken for an answer to a request, found to answer none, and
         * lost. Both say what they are, and are recognised by that.
         */
        fun read(payload: ByteArray): ContactMessage? =
            ProfileKeyUpdate.parse(plaintext = payload)?.let { ProfileKey(update = it) }
                ?: KeyRequest.takeIf { ProfileKeyRequest.matches(plaintext = payload) }
                ?: ContactCard.parse(plaintext = payload)?.let { Answer(card = it) }
                ?: Removal.takeIf { ContactRemoval.matches(plaintext = payload) }
    }
}
