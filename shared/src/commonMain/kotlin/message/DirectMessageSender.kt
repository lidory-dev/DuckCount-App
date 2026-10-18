package message

import channel.DirectChannelError
import channel.DirectChannelRepository
import database.data.remote.DirectMessageApi
import database.data.remote.DirectMessageKindDto
import database.data.remote.SendDirectMessageBody
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
 */
class DirectMessageSender(
    private val directMessageApi: DirectMessageApi,
    private val directChannelRepository: DirectChannelRepository,
    private val mlsIdentityRepository: MlsIdentityRepository,
) {

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
        val channel = when (
            val ensured = directChannelRepository.ensureChannel(peerUuid = recipientUserUuid)
        ) {
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
            directChannelRepository.noteWelcomeAttached(peerUuid = recipientUserUuid)
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
        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(DirectSendError.Channel(DirectChannelError.Identity(opened.error)))
        }

        val message = try {
            client.encryptApplicationMessage(mlsGroupId = mlsGroupId, payload = payload)
        } catch (exception: MlsException) {
            return Result.Error(
                if (exception.isRetryable()) DirectSendError.Storage else DirectSendError.GroupUnusable,
            )
        }

        val ciphertext = DirectMessageEnvelope(
            mlsGroupId = mlsGroupId,
            message = message,
            welcome = welcome,
        ).serialize()

        val send = directMessageApi.sendDirectMessage(
            body = SendDirectMessageBody(
                recipientUserUuid = recipientUserUuid,
                ciphertext = ciphertext,
                kind = kind,
            ),
        )

        return when (send) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> Result.Error(
                when (send.error) {
                    NetworkError.NOT_FOUND -> DirectSendError.RecipientGone
                    NetworkError.TOO_MANY_REQUESTS -> DirectSendError.RateLimited
                    else -> DirectSendError.Network(send.error)
                },
            )
        }
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

    /** The recipient's account is gone. Nobody is going to receive this. */
    data object RecipientGone : DirectSendError

    /**
     * The sender has spent their allowance of messages.
     *
     * A single user action can be many messages, one per person an expense names, so this
     * is a state a caller has to expect rather than an abuse of the API. Sending more only
     * pushes the count further past the limit; the rest wait.
     */
    data object RateLimited : DirectSendError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : DirectSendError
}

/**
 * Whether the message certainly never reached the server, which counts one against the user's
 * allowance only once it is posted: it failed on this device first, building the channel or
 * encrypting, or the host never resolved, so nothing was sent. Every other failure is taken as
 * posted. Some were: refused for the limit, sent to an account that is gone, a `5xx` the proxy
 * may return after the message was stored. The rest cannot be told from those: a timeout, or
 * a server out of reach, which `networking.Client` reports the same whether the connection was
 * refused before anything was written or dropped after.
 *
 * What tells a request whose budget a peer was charged for, see [util.spendMessageBudget], from
 * one whose budget goes back.
 */
internal fun DirectSendError.neverReachedServer(): Boolean =
    when (this) {
        is DirectSendError.Channel, DirectSendError.Storage, DirectSendError.GroupUnusable -> true
        is DirectSendError.Network -> error == NetworkError.NO_INTERNET
        DirectSendError.RecipientGone, DirectSendError.RateLimited -> false
    }
