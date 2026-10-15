package expense

import authenticationUtils.getAuthenticatedUserUuid
import channel.DirectChannelRepository
import channel.Reply
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
 * **Nor from just any creditor.** The server puts the user on any expense it is asked to,
 * and a message is read from anybody pinned: whoever the user sent a contact request to, and
 * a contact either of them removed. So a key is only kept from a contact, or for an expense
 * this device was already waiting for when its creditor stopped being one, see
 * [ExpectedKeys]: a former contact can still hand back what they owed, and nothing they put
 * the user on since.
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
    private val directChannelRepository: DirectChannelRepository,
    private val mlsIdentityRepository: MlsIdentityRepository,
) : DirectMessageHandler {

    /** The requests the pass under way has read, answered once it is over, see [finishPass]. */
    private val requests = KeyRequestsInPass()

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
            requests.add(
                requesterUuid = senderUserUuid,
                mlsGroupId = mlsGroupId,
                expenseUuids = request.expenseUuids,
            )

            return DirectMessageHandling.HANDLED
        }

        return DirectMessageHandling.NOT_MINE
    }

    /** Answers each asker of the pass once, see [KeyRequestsInPass]. */
    override suspend fun finishPass() {
        // Before the requests are taken: on a device locked since the pass began this throws,
        // and they are kept for the next one, which the messages left unacknowledged bring.
        val ownUserUuid = getAuthenticatedUserUuid()

        val asked = requests.drain()

        if (ownUserUuid == null) return

        asked.forEach {
            answer(
                ownUserUuid = ownUserUuid,
                requesterUuid = it.requesterUuid,
                mlsGroupId = it.mlsGroupId,
                expenseUuids = it.expenseUuids,
            )
        }
    }

    /**
     * Keeps the keys [senderUuid] handed over that this device takes from them, see
     * [recordsFrom], to be verified once their expenses open under them and carry
     * [senderUuid]'s signature.
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

        val records = share.recordsFrom(
            senderUuid = senderUuid,
            senderSignatureKey = senderSignatureKey,
            expected = keyRepository.expectedFrom(creditorUuid = senderUuid),
        )

        records.forEach { keyRepository.storeUnverified(record = it) }
    }

    /**
     * Hands back the keys [requesterUuid] is entitled to, of [expenseUuids], which they asked
     * for in the group [mlsGroupId].
     *
     * The answer goes back in the group the question was authenticated in, not through
     * whatever this device currently records as the channel to them: that needs no key
     * package, works whatever state the channel is in, and cannot be pointed at anybody
     * else by the asker.
     *
     * An entitlement that does not hold is silently nothing. Telling an asker that an
     * expense exists but is not theirs is telling them something.
     *
     * Nor is an asker answered every time they ask: each answer is one of the user's own
     * allowance of messages, and anybody named on one of the user's expenses could otherwise
     * spend it a request at a time. They get one answer a pass at most, see
     * [KeyRequestsInPass], out of a budget of two at once and one more an hour after that,
     * see [DirectChannelRepository.claimReply], and a request past it is dropped the way one
     * owed nothing is. An honest asker asks again on their own backoff, see
     * [OneTimeExpenseSync.requestMissingKeys]: an answer lost on the way is answered again at
     * their next try if it is still the only reply outstanding to them, and whatever came
     * before, at their first try an hour after the last reply this device sent them at the
     * latest.
     *
     * A key the answer hands over is no longer owed to the asker, see
     * [database.data.local.entity.OwedExpenseKeyEntity], or the next sync would hand it
     * over again.
     */
    private suspend fun answer(
        ownUserUuid: String,
        requesterUuid: String,
        mlsGroupId: ByteArray,
        expenseUuids: List<String>,
    ) {
        val answers = expenseUuids.mapNotNull { expenseUuid ->
            keyRepository.record(expenseUuid = expenseUuid)
                ?.keyFor(requesterUuid = requesterUuid, ownUserUuid = ownUserUuid)
        }

        // Before the budget is asked: an asker owed nothing spends none of it.
        if (answers.isEmpty()) return

        if (!directChannelRepository.claimReply(peerUuid = requesterUuid, reply = Reply.EXPENSE_KEYS)) return

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
 * The key requests one pass of the sync has read, as one answer per asker.
 *
 * A pass reads every message waiting, and an asker's tries pile up while this device is
 * away or locked: copies of one request, or, from an asker who logged out and back in, some
 * in a group they no longer hold and then one in the group they built since. Answered one by
 * one, the copies would each spend a reply of the asker's budget, the ones in the lost group
 * first, and the request they can read the answer to would find it spent. Kept until the
 * pass is over instead, each asker gets one answer, in the group of their latest request,
 * which is the group they are in now.
 *
 * It carries everything they asked for, latest first, as much as one answer holds, see
 * [ExpenseKeyShare.MAX_KEYS]: an earlier request can name an expense a later one does not,
 * when the asker's rows for it wait out a longer backoff than the rest.
 *
 * Only the sync touches it, one pass at a time, see [message.DirectMessageSync].
 */
internal class KeyRequestsInPass {

    private val byRequester = LinkedHashMap<String, AskedKeys>()

    /** Notes that [requesterUuid] asked for [expenseUuids] in the group [mlsGroupId], after anything they asked before. */
    fun add(requesterUuid: String, mlsGroupId: ByteArray, expenseUuids: List<String>) {
        val earlier = byRequester[requesterUuid]?.expenseUuids.orEmpty()

        byRequester[requesterUuid] = AskedKeys(
            requesterUuid = requesterUuid,
            mlsGroupId = mlsGroupId,
            expenseUuids = (expenseUuids + earlier).distinct().take(ExpenseKeyShare.MAX_KEYS),
        )
    }

    /** What each asker of the pass asked for, leaving nothing for the next one. */
    fun drain(): List<AskedKeys> {
        val asked = byRequester.values.toList()

        byRequester.clear()

        return asked
    }
}

/** Everything [requesterUuid] asked for in one pass, and the group of their latest request. */
internal class AskedKeys(
    val requesterUuid: String,
    val mlsGroupId: ByteArray,
    val expenseUuids: List<String>,
)

/**
 * The records to keep of this share's keys, which [senderUuid] handed over in a message MLS
 * authenticated them with [senderSignatureKey] in.
 *
 * The whole decision on a share, kept apart from the message plumbing so it can be stated and
 * tested on its own, as [keyFor] is for a request. A key is kept when:
 *
 * - its nonce shows [senderUuid] to be the expense's creditor, see [OneTimeExpenseKeyRecord.nonce];
 * - it is one this device takes from them, see [ExpectedKeys]: any from a contact, and from
 *   anybody else only one it was already waiting for when they stopped being a contact.
 *
 * Each record is built from the sender MLS authenticated, the signature key it authenticated
 * them with, and the beneficiaries the message carried, never from what the server says about
 * the expense.
 */
internal fun ExpenseKeyShare.recordsFrom(
    senderUuid: String,
    senderSignatureKey: ByteArray,
    expected: ExpectedKeys,
): List<OneTimeExpenseKeyRecord> =
    keys.mapNotNull { key ->
        // Only a UUID written as the server writes one, which is the only kind the check
        // below can show anybody to be the creditor of anyway. Anything else would reach
        // the library, which throws on a string that is not valid UTF-16 rather than
        // refusing it, and a contact could crash the sync with one.
        if (!key.expenseUuid.isCanonicalUuid()) return@mapNotNull null

        val isCreditor = isOneTimeExpenseCreditor(
            expenseUuid = key.expenseUuid,
            creditorUuid = senderUuid,
            nonce = key.nonce,
        )

        if (!isCreditor) return@mapNotNull null

        if (!expected.includes(expenseUuid = key.expenseUuid)) return@mapNotNull null

        OneTimeExpenseKeyRecord(
            expenseUuid = key.expenseUuid,
            key = key.key,
            creditorUuid = senderUuid,
            nonce = key.nonce,
            creditorSignatureKey = senderSignatureKey,
            beneficiaryUuids = key.beneficiaryUuids,
        )
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
