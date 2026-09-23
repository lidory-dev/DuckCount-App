package expense

import authenticationUtils.getAuthenticatedUserUuid
import message.DirectMessageHandler
import message.DirectMessageHandling
import message.DirectMessageSender
import database.data.remote.DirectMessageKindDto

/**
 * The direct messages that carry the keys to one-time expenses: a creditor handing them
 * over, and a participant asking for them again.
 *
 * **A share is only ever believed from the creditor.** MLS says who sent a message, and the
 * key to an expense is the creditor's to give: taking one from anybody else would let a
 * co-beneficiary hand over thirty-two bytes of their choosing and have them stored as the
 * key. The bytes still have to open the expense before they are acted on, which is the gate
 * that actually holds, but there is no reason to store a stranger's guess in the first
 * place.
 *
 * **A request is only ever answered by the creditor**, out of the record in their own
 * vault, which is the only account of who an expense was meant for that the server cannot
 * rewrite. Asking `GET /expenses` instead would mean a server that wanted a key could
 * simply add a beneficiary and ask for it.
 */
internal class OneTimeExpenseMessageHandler(
    private val keyRepository: OneTimeExpenseKeyRepository,
    private val directMessageSender: DirectMessageSender,
) : DirectMessageHandler {

    override suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling {
        ExpenseKeyShare.parse(plaintext = payload)?.let { share ->
            receive(creditorUuid = senderUserUuid, share = share)

            return DirectMessageHandling.HANDLED
        }

        ExpenseKeyRequest.parse(plaintext = payload)?.let { request ->
            answer(
                requesterUuid = senderUserUuid,
                mlsGroupId = mlsGroupId,
                request = request,
            )

            return DirectMessageHandling.HANDLED
        }

        return DirectMessageHandling.NOT_MINE
    }

    /**
     * Keeps the keys [creditorUuid] handed over, to be believed once they open the expenses
     * they name.
     *
     * The record is built from the sender MLS authenticated and the beneficiaries the
     * message carried, never from what the server says about the expense.
     */
    private suspend fun receive(creditorUuid: String, share: ExpenseKeyShare) {
        share.keys.forEach { key ->
            keyRepository.storeUnverified(
                record = OneTimeExpenseKeyRecord(
                    expenseUuid = key.expenseUuid,
                    key = key.key,
                    creditorUuid = creditorUuid,
                    beneficiaryUuids = key.beneficiaryUuids,
                ),
            )
        }
    }

    /**
     * Hands back the keys [requesterUuid] is entitled to, of those they asked for.
     *
     * The answer goes back in the group the question was authenticated in, not through
     * whatever this device currently believes the channel to them is: that needs no key
     * package, works whatever state the channel is in, and cannot be pointed at anybody
     * else by the asker.
     *
     * An entitlement that does not hold is silently nothing. Telling an asker that an
     * expense exists but is not theirs is telling them something.
     */
    private suspend fun answer(
        requesterUuid: String,
        mlsGroupId: ByteArray,
        request: ExpenseKeyRequest,
    ) {
        val ownUserUuid = getAuthenticatedUserUuid() ?: return

        val answers = request.expenseUuids.mapNotNull { expenseUuid ->
            keyRepository.record(expenseUuid = expenseUuid)
                ?.keyFor(requesterUuid = requesterUuid, ownUserUuid = ownUserUuid)
        }

        if (answers.isEmpty()) return

        directMessageSender.replyIn(
            recipientUserUuid = requesterUuid,
            mlsGroupId = mlsGroupId,
            payload = ExpenseKeyShare(keys = answers).serialize(),
            // The answer makes an expense readable just as the first share did.
            kind = DirectMessageKindDto.ONE_TIME_EXPENSE,
        )
    }
}

/**
 * The key to hand [requesterUuid], or null when they are owed nothing.
 *
 * The whole authorization decision, kept apart from the message plumbing so it can be
 * stated and tested on its own. Two conditions, both against this record and nothing else:
 *
 * - the user is the creditor, since the key is the creditor's to give;
 * - the requester is somebody this record names.
 *
 * The record is the creditor's own account of who the expense was meant for, written when
 * they created it and sealed in their vault. Deciding this from `GET /expenses` instead
 * would mean a server that wanted the key could add itself a beneficiary row and ask.
 */
internal fun OneTimeExpenseKeyRecord.keyFor(
    requesterUuid: String,
    ownUserUuid: String,
): ExpenseKey? {
    if (creditorUuid != ownUserUuid) return null

    if (!isParticipant(userUuid = requesterUuid)) return null

    return ExpenseKey(
        expenseUuid = expenseUuid,
        key = key,
        beneficiaryUuids = beneficiaryUuids,
    )
}
