package message

import channel.DirectChannel
import channel.DirectChannelError
import channel.DirectChannelRepository
import database.data.remote.DirectMessageApi
import database.data.remote.DirectMessageKindDto
import database.data.remote.ListDirectMessagesResponse
import database.data.remote.MessageLimitScope
import database.data.remote.SendDirectMessageBody
import database.data.remote.SendDirectMessageError
import database.data.repository.ContactRepository
import mls.MlsIdentityRepository
import mls.encryptApplicationMessage
import mls.isRetryable
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.Error
import util.NetworkError
import util.Result

/**
 * Sends one user a message only they can read.
 *
 * The payload is encrypted to the MLS group the two of them share, built on demand when
 * this device holds none, and the server is handed an opaque string addressed to a user
 * uuid. What goes inside is the caller's business; this is the envelope and the channel.
 *
 * Except for what the server says of the recipient's account. One who deleted it is forgotten
 * as a contact here, see [ContactRepository.receiveAccountDeletion], before the caller is told
 * so, see [DirectSendError.RecipientDeleted], whatever the message was: the server tells nobody
 * of a deletion, and a message to them is how this device finds out.
 *
 * Not while anything they sent is still waiting for this device to read. The server keeps what
 * a deleted account sent, the key to an expense of theirs for instance, which opens only in the
 * groups forgetting them deletes, and they never send it again. So they are forgotten once it
 * is read, as their notice of the deletion, sent last, would have them: by that notice, should it
 * be among what is waiting, see [AccountDeletionNotice][contact.AccountDeletionNotice], or else by
 * the next message to them, which finds the account gone and nothing of theirs left. The server
 * not saying what is waiting leaves them to that next message too.
 *
 * [ensureChannel] and [noteWelcomeAttached] are [DirectChannelRepository.ensureChannel] and
 * [DirectChannelRepository.noteWelcomeAttached], [encrypt] opens the user's MLS client and
 * encrypts with it, see [encryptIn], [post] is [DirectMessageApi.sendDirectMessage], [listWaiting]
 * is [DirectMessageApi.getDirectMessages] and [receiveAccountDeletion] is
 * [ContactRepository.receiveAccountDeletion], or what a test stands in for any of them.
 */
class DirectMessageSender internal constructor(
    private val ensureChannel: suspend (peerUuid: String) -> Result<DirectChannel, DirectChannelError>,
    private val noteWelcomeAttached: suspend (peerUuid: String) -> Unit,
    private val encrypt: suspend (mlsGroupId: ByteArray, payload: ByteArray) -> Result<ByteArray, DirectSendError>,
    private val post: suspend (body: SendDirectMessageBody) -> EmptyResult<SendDirectMessageError>,
    private val listWaiting: suspend () -> Result<ListDirectMessagesResponse, NetworkError>,
    private val receiveAccountDeletion: suspend (userUuid: String) -> Unit,
) {
    constructor(
        directMessageApi: DirectMessageApi,
        directChannelRepository: DirectChannelRepository,
        mlsIdentityRepository: MlsIdentityRepository,
        contactRepository: ContactRepository,
    ) : this(
        ensureChannel = directChannelRepository::ensureChannel,
        noteWelcomeAttached = directChannelRepository::noteWelcomeAttached,
        encrypt = { mlsGroupId, payload ->
            encryptIn(mlsIdentityRepository = mlsIdentityRepository, mlsGroupId = mlsGroupId, payload = payload)
        },
        post = directMessageApi::sendDirectMessage,
        listWaiting = directMessageApi::getDirectMessages,
        receiveAccountDeletion = contactRepository::receiveAccountDeletion,
    )

    /**
     * Sends [payload] to [recipientUserUuid], building a channel to them if there is none.
     *
     * The channel's pending Welcome rides along while the recipient has not been seen in
     * the group, which is what lets somebody who logged out be reached again.
     */
    suspend fun send(
        recipientUserUuid: String,
        payload: ByteArray,
        kind: DirectMessageKindDto = DirectMessageKindDto.MESSAGE,
    ): EmptyResult<DirectSendError> {
        val channel = when (val ensured = ensureChannel(recipientUserUuid)) {
            is Result.Success -> ensured.data
            is Result.Error -> return Result.Error(DirectSendError.Channel(ensured.error))
        }

        val sent = sendIn(
            recipientUserUuid = recipientUserUuid,
            mlsGroupId = channel.mlsGroupId,
            welcome = channel.welcome,
            payload = payload,
            kind = kind,
        )

        if (sent is Result.Success && channel.welcome != null) {
            noteWelcomeAttached(recipientUserUuid)
        }

        return sent
    }

    /**
     * Sends [payload] to [recipientUserUuid] in [mlsGroupId], without touching the stored
     * channel.
     *
     * What an answer uses: replying in the group the question was authenticated in needs no
     * key package, works whatever this device thinks the channel to them is, and cannot be
     * pointed anywhere by the asker, since the group is the one this device read them in.
     *
     * The asker decides how often this is called, so every caller claims the reply from the
     * asker's budget first, see [channel.DirectChannelRepository.claimReply]: one that did not
     * would let them spend the user's allowance of messages one for one.
     */
    suspend fun replyIn(
        recipientUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
        kind: DirectMessageKindDto = DirectMessageKindDto.MESSAGE,
    ): EmptyResult<DirectSendError> =
        sendIn(
            recipientUserUuid = recipientUserUuid,
            mlsGroupId = mlsGroupId,
            welcome = null,
            payload = payload,
            kind = kind,
        )

    private suspend fun sendIn(
        recipientUserUuid: String,
        mlsGroupId: ByteArray,
        welcome: ByteArray?,
        payload: ByteArray,
        kind: DirectMessageKindDto,
    ): EmptyResult<DirectSendError> {
        val message = when (val encrypted = encrypt(mlsGroupId, payload)) {
            is Result.Success -> encrypted.data
            is Result.Error -> return encrypted
        }

        val ciphertext = DirectMessageEnvelope(
            mlsGroupId = mlsGroupId,
            message = message,
            welcome = welcome,
        ).serialize()

        val send = post(
            SendDirectMessageBody(
                recipientUserUuid = recipientUserUuid,
                ciphertext = ciphertext,
                kind = kind,
            ),
        )

        return when (send) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> Result.Error(
                when (val error = send.error) {
                    is SendDirectMessageError.RateLimited -> DirectSendError.RateLimited(
                        retryAfterSeconds = error.retryAfterSeconds,
                        scope = error.scope,
                    )

                    SendDirectMessageError.RecipientDeleted -> {
                        if (hasNothingWaitingFrom(senderUserUuid = recipientUserUuid)) {
                            receiveAccountDeletion(recipientUserUuid)
                        }

                        DirectSendError.RecipientDeleted
                    }

                    is SendDirectMessageError.Network -> DirectSendError.Network(error.error)
                },
            )
        }
    }

    /**
     * Whether the server holds nothing [senderUserUuid] sent that this device has yet to read, see
     * [DirectMessageSender]. False when it could not be asked.
     *
     * Not waiting for a [DirectMessageSync] under way, as [DirectMessageSync.hasNothingWaiting]
     * does: the pass sends too, and would wait on itself. What it read and has yet to acknowledge
     * is still listed, which leaves the sender to their notice of the deletion, read in that pass,
     * or to the next message to them.
     */
    private suspend fun hasNothingWaitingFrom(senderUserUuid: String): Boolean {
        val listed = listWaiting()

        return listed is Result.Success && listed.data.messages.none { it.senderUserUuid == senderUserUuid }
    }
}

/**
 * Encrypts [payload] to the group [mlsGroupId] with the user's own MLS client, what
 * [DirectMessageSender] sends: a group that cannot be used for good is told apart from storage
 * that failed this once.
 */
private suspend fun encryptIn(
    mlsIdentityRepository: MlsIdentityRepository,
    mlsGroupId: ByteArray,
    payload: ByteArray,
): Result<ByteArray, DirectSendError> {
    val client = when (val opened = mlsIdentityRepository.client()) {
        is Result.Success -> opened.data
        is Result.Error -> return Result.Error(DirectSendError.Channel(DirectChannelError.Identity(opened.error)))
    }

    return try {
        Result.Success(client.encryptApplicationMessage(mlsGroupId = mlsGroupId, payload = payload))
    } catch (exception: MlsException) {
        Result.Error(if (exception.isRetryable()) DirectSendError.Storage else DirectSendError.GroupUnusable)
    }
}

/** Why a direct message did not go out. */
sealed interface DirectSendError : Error {

    /** No channel to the recipient could be had. */
    data class Channel(val error: DirectChannelError) : DirectSendError

    /**
     * The group is there but unusable for good, so nothing can be sent in it.
     *
     * Distinct from [Storage], which the same call may survive later.
     */
    data object GroupUnusable : DirectSendError

    /** The user's own storage failed, so the same message can be sent again later. */
    data object Storage : DirectSendError

    /**
     * The recipient deleted their account, see [SendDirectMessageError.RecipientDeleted]. Nobody
     * is going to receive this, nor anything after it.
     *
     * A contact is forgotten by the time a caller reads this, see
     * [ContactRepository.receiveAccountDeletion], unless something they sent is still waiting to
     * be read, see [DirectMessageSender]: they are forgotten after it, and a pass may try them
     * once more meanwhile. What the caller kept for them is its own to drop either way.
     */
    data object RecipientDeleted : DirectSendError

    /**
     * Refused for one of the server's two limits on sending, the one [scope] names, which
     * lets a message through again in [retryAfterSeconds].
     *
     * A single user action can be many messages, one per person an expense names, so this
     * is a state a caller has to expect rather than an abuse of the API. Past what the user
     * may send this recipient, the others can still be tried, though what they may send
     * everybody can refuse them in turn; past that, sending more only pushes the count further
     * past the limit, and the rest wait. Either way what a pass leaves waiting is sent again
     * once the limit reopens, see [ResendScheduler].
     */
    data class RateLimited(val retryAfterSeconds: Long, val scope: MessageLimitScope) : DirectSendError

    /**
     * The server could not be reached, or refused the call.
     *
     * A `404` included, [NetworkError.NOT_FOUND]: a recipient the server knows nothing of, which
     * for somebody the server once named to this device means an account an administrator removed,
     * or the proxy in front of the API, which answers one for every route while the API is being
     * redeployed. Neither says the user deleted their account, so nothing a caller drops on
     * [RecipientDeleted] is dropped on it.
     *
     * Nor do the passes that send in the background report it, though they keep what they would
     * after any other failure and try again on their own schedule: the account an administrator
     * removed answers it for good, which no retry of the sync changes, and a redeploy fails the
     * reads a sync starts with, which report it.
     */
    data class Network(val error: NetworkError) : DirectSendError
}

/**
 * Whether the message certainly never reached the server, which counts one against the user's
 * allowance only once it is posted: it failed on this device first, building the channel or
 * encrypting, or the host never resolved, so nothing was sent. Every other failure is taken as
 * posted. Some were: refused for the limit, sent to an account that was deleted, a `5xx` the proxy
 * may return after the message was stored. The rest cannot be told from those: a timeout, or
 * a server out of reach, which `networking.Client` reports the same whether the connection was
 * refused before anything was written or dropped after.
 *
 * One of the two failures a request gives its peer's budget back for, see [givesBudgetBack].
 */
internal fun DirectSendError.neverReachedServer(): Boolean =
    when (this) {
        is DirectSendError.Channel, DirectSendError.Storage, DirectSendError.GroupUnusable -> true
        is DirectSendError.Network -> error == NetworkError.NO_INTERNET
        DirectSendError.RecipientDeleted, is DirectSendError.RateLimited -> false
    }

/**
 * Whether a request sent out of a peer's budget, see [util.spendMessageBudget], gives it back for
 * failing this way: it certainly never reached the server, see [neverReachedServer], or the
 * server refused it for a limit, see [DirectSendError.RateLimited].
 *
 * A refused request is booked to go out again once the limit reopens, see [ResendScheduler], and
 * the budget it kept would hold back that very resend, two at once being all it allows. Nor does
 * a refusal spend what the budget keeps, the user's allowance for everybody else. Refused for
 * this peer, it is refused before what the user may send everybody is counted; refused for
 * everybody, the user is past that already, and what it costs on top is one of what they may send
 * this peer, which holds back no message to anybody else.
 */
internal fun DirectSendError.givesBudgetBack(): Boolean =
    neverReachedServer() || this is DirectSendError.RateLimited
