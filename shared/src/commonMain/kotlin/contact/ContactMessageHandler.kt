package contact

import database.data.repository.ContactRepository
import message.DirectMessageHandler
import message.DirectMessageHandling

/**
 * The direct messages that are about the contact relationship itself: a card accepting a
 * request the user sent, and a notice that a contact removed the user.
 *
 * A card carries no `type`, it predates there being more than one kind of direct message,
 * so it is recognised by its shape. Everything added since says what it is.
 */
internal class ContactMessageHandler(
    private val contactRepository: ContactRepository,
) : DirectMessageHandler {

    override suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling {
        ContactCard.parse(plaintext = payload)?.let { card ->
            contactRepository.receiveCard(senderUserUuid = senderUserUuid, card = card)

            return DirectMessageHandling.HANDLED
        }

        if (ContactRemoval.matches(plaintext = payload)) {
            contactRepository.receiveRemoval(senderUserUuid = senderUserUuid)

            return DirectMessageHandling.HANDLED
        }

        return DirectMessageHandling.NOT_MINE
    }
}
