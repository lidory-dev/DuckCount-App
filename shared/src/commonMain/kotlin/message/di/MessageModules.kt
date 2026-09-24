package message.di

import contact.ContactMessageHandler
import expense.OneTimeExpenseMessageHandler
import message.DirectMessageHandler
import message.DirectMessageSender
import message.DirectMessageSync
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val messageModule = module {
    singleOf(::DirectMessageSender)

    /**
     * The handlers, in the order a message is offered to them.
     *
     * Listed by hand rather than collected, because the order is part of the behaviour: a
     * contact card is recognised by its shape rather than by a `type`, so anything that
     * could be mistaken for one has to be offered the message first.
     */
    single<List<DirectMessageHandler>> {
        listOf(
            ContactMessageHandler(contactRepository = get()),
            OneTimeExpenseMessageHandler(
                keyRepository = get(),
                directMessageSender = get(),
                mlsIdentityRepository = get(),
            ),
        )
    }

    single {
        DirectMessageSync(
            directMessageApi = get(),
            directChannelRepository = get(),
            contactRepository = get(),
            mlsIdentityRepository = get(),
            handlers = get(),
        )
    }
}
