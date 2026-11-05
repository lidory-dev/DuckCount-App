package message.di

import contact.ContactMessageHandler
import expense.OneTimeExpenseMessageHandler
import message.DirectMessageHandler
import message.DirectMessageSender
import message.DirectMessageSync
import org.koin.dsl.module

val messageModule = module {
    single {
        DirectMessageSender(
            directMessageApi = get(),
            directChannelRepository = get(),
            mlsIdentityRepository = get(),
            contactRepository = get(),
        )
    }

    /**
     * The handlers, in the order a message is offered to them.
     *
     * Listed by hand rather than collected, because the order is part of the behaviour: a
     * contact card is recognised by its shape rather than by a `type`, so anything that
     * could be mistaken for one has to be offered the message first.
     */
    single<List<DirectMessageHandler>> {
        listOf(
            ContactMessageHandler(
                contactRequestRepository = get(),
                contactRepository = get(),
                profileKeyPull = get(),
            ),
            OneTimeExpenseMessageHandler(
                keyRepository = get(),
                owedExpenseKeyDao = get(),
                directMessageSender = get(),
                directChannelRepository = get(),
                mlsIdentityRepository = get(),
            ),
        )
    }

    single {
        DirectMessageSync(
            directMessageApi = get(),
            directChannelRepository = get(),
            contactRepository = get(),
            contactRequestRepository = get(),
            directMessageSender = get(),
            mlsIdentityRepository = get(),
            identityConflictDao = get(),
            blockedUserDao = get(),
            groupDao = get(),
            handlers = get(),
        )
    }
}
