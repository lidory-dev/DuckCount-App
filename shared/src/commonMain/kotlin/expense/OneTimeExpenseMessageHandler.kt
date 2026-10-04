package expense

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.OwedExpenseKeyDao
import message.DirectMessageHandler
import message.DirectMessageHandling
import message.DirectMessageSender
import database.data.remote.DirectMessageKindDto
import mls.MlsIdentityRepository
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.isOneTimeExpenseCreditor
import util.Result

/**
 * The direct messages that carry the keys to one-time expenses: a creditor handing them
 * over, and a participant asking for them again.
 *
 * **A share is only ever accepted from the creditor.** MLS says who sent a message, and the
 * key to an expense is the creditor's to give: taking one from anybody else would let a
 * co-beneficiary hand over thirty-two bytes of their choosing in place of the creditor's.
 * The expense's UUID says who the creditor is, with no need to ask the server: it was
 * derived from their UUID and a nonce they drew, which travels with each key, and nobody
 * else can find a nonce that derives it from their own, see [OneTimeExpenseKeyRecord.nonce].
 * A key the nonce does not show its sender to be the creditor of is dropped as it arrives.
 *
 * Even the creditor's is only kept unverified until the expense opens under it, see
 * [database.data.local.entity.UnverifiedExpenseKeyEntity].
 *
 * **A request is only ever answered by the creditor**, out of their own record of the
 * expense, which is the only account of who it was meant for that the server cannot
 * rewrite. Asking `GET /expenses` instead would mean a server that wanted a key could
 * simply add a beneficiary and ask for it.
 */
internal class OneTimeExpenseMessageHandler(
    private val keyRepository: OneTimeExpenseKeyRepository,
    private val owedExpenseKeyDao: OwedExpenseKeyDao,
    private val directMessageSender: DirectMessageSender,
    private val mlsIdentityRepository: MlsIdentityRepository,
) : DirectMessageHandler {

    override suspend fun handle(
        senderUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ): DirectMessageHandling {
        ExpenseKeyShare.parse(plaintext = payload)?.let { share ->
            receive(senderUuid = senderUserUuid, share = share)

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
     * Keeps the keys [senderUuid] handed over to expenses they are the creditor of, to be
     * verified once those open under them and carry [senderUuid]'s signature.
     *
     * The record is built from the sender MLS authenticated, the signature key it
     * authenticated them with, and the beneficiaries the message carried, never from what
     * the server says about the expense.
     */
    private suspend fun receive(senderUuid: String, share: ExpenseKeyShare) {
        // Every channel pins its peer, and this message was just read in one, so both are
        // there: the client read it, and the pin checked its sender.
        val client = (mlsIdentityRepository.client() as? Result.Success)?.data ?: return
        // A pin that cannot be read leaves the keys unkept, with nothing to check them
        // against. The expenses stay unopened, and their keys are asked for again.
        val senderSignatureKey = try {
            client.pinnedSignatureKeyFor(peerUuid = senderUuid)
        } catch (_: MlsException) {
            return
        } ?: return

        share.keys.forEach { key ->
            // Only a UUID written as the server writes one, which is the only kind the check
            // below can show anybody to be the creditor of anyway. Anything else would reach
            // the library, which throws on a string that is not valid UTF-16 rather than
            // refusing it, and a contact could crash the sync with one.
            if (!key.expenseUuid.isCanonicalUuid()) return@forEach

            val isCreditor = isOneTimeExpenseCreditor(
                expenseUuid = key.expenseUuid,
                creditorUuid = senderUuid,
                nonce = key.nonce,
            )

            if (!isCreditor) return@forEach

            keyRepository.storeUnverified(
                record = OneTimeExpenseKeyRecord(
                    expenseUuid = key.expenseUuid,
                    key = key.key,
                    creditorUuid = senderUuid,
                    nonce = key.nonce,
                    creditorSignatureKey = senderSignatureKey,
                    beneficiaryUuids = key.beneficiaryUuids,
                ),
            )
        }
    }

    /**
     * Hands back the keys [requesterUuid] is entitled to, of those they asked for.
     *
     * The answer goes back in the group the question was authenticated in, not through
     * whatever this device currently records as the channel to them: that needs no key
     * package, works whatever state the channel is in, and cannot be pointed at anybody
     * else by the asker.
     *
     * An entitlement that does not hold is silently nothing. Telling an asker that an
     * expense exists but is not theirs is telling them something.
     *
     * A key the answer hands over is no longer owed to the asker, see
     * [database.data.local.entity.OwedExpenseKeyEntity], or the next sync would hand it
     * over again.
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

        val replied = directMessageSender.replyIn(
            recipientUserUuid = requesterUuid,
            mlsGroupId = mlsGroupId,
            payload = ExpenseKeyShare(keys = answers).serialize(),
            // The answer makes an expense readable just as the first share did.
            kind = DirectMessageKindDto.ONE_TIME_EXPENSE,
        )

        if (replied is Result.Success) {
            owedExpenseKeyDao.delete(
                recipientUuid = requesterUuid,
                expenseUuids = answers.map { it.expenseUuid },
            )
        }
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
 * they created it and sealed under a key the server does not have. Deciding this from
 * `GET /expenses` instead would mean a server that wanted the key could add itself a
 * beneficiary row and ask.
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
        nonce = nonce,
        beneficiaryUuids = beneficiaryUuids,
    )
}
