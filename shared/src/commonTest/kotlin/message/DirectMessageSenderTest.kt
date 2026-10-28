package message

import channel.DirectChannel
import database.data.remote.DirectMessageDto
import database.data.remote.ListDirectMessagesResponse
import database.data.remote.MessageLimitScope
import database.data.remote.SendDirectMessageBody
import database.data.remote.SendDirectMessageError
import kotlinx.coroutines.runBlocking
import networking.pageOf
import networking.readWholeListing
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"

/**
 * What the server's answer to a direct message to Alice, one of the user's contacts, makes of her,
 * with the channel, the encryption and the server stood in for.
 *
 * Only a `410` says she deleted her account, and only on it is she forgotten, whichever pass was
 * sending, once nothing she sent is left waiting to be read: what the passes drop on
 * [DirectSendError.RecipientDeleted] they read off this. The server saying so in answer to
 * anything else forgets her the same way, see [DirectMessageSender.receiveAccountDeletions].
 */
class DirectMessageSenderTest {

    /** What the server answers the next message. */
    private var answer: EmptyResult<SendDirectMessageError> = Result.Success(Unit)

    /** Who was forgotten for deleting their account, see `ContactRepository.receiveAccountDeletion`. */
    private val forgotten = mutableListOf<String>()

    /** The messages that reached the server. */
    private val posted = mutableListOf<SendDirectMessageBody>()

    /** What the server lists as waiting for the user, nothing unless a test says otherwise. */
    private var waiting: Result<ListDirectMessagesResponse, NetworkError> =
        Result.Success(ListDirectMessagesResponse(messages = emptyList()))

    private val sender = DirectMessageSender(
        ensureChannel = { Result.Success(DirectChannel(mlsGroupId = byteArrayOf(1, 2, 3), welcome = null)) },
        noteWelcomeAttached = { },
        encrypt = { _, payload -> Result.Success(payload) },
        post = { body ->
            posted += body
            answer
        },
        listWaiting = { waiting },
        receiveAccountDeletion = { userUuid -> forgotten += userUuid },
    )

    @Test
    fun `a contact who deleted their account is forgotten before the caller is told`() = runBlocking<Unit> {
        answer = Result.Error(SendDirectMessageError.RecipientDeleted)

        val sent = sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

        assertEquals(Result.Error(DirectSendError.RecipientDeleted), sent)
        assertEquals(listOf(ALICE), forgotten)
    }

    /** An answer goes through the same, the key a contact asked for included. */
    @Test
    fun `a reply to a contact who deleted their account forgets them too`() = runBlocking<Unit> {
        answer = Result.Error(SendDirectMessageError.RecipientDeleted)

        val sent = sender.replyIn(recipientUserUuid = ALICE, mlsGroupId = byteArrayOf(4, 5, 6), payload = byteArrayOf(9))

        assertEquals(Result.Error(DirectSendError.RecipientDeleted), sent)
        assertEquals(listOf(ALICE), forgotten)
    }

    /** Somebody else's messages waiting say nothing of what Alice sent. */
    @Test
    fun `a contact who deleted their account is forgotten when only others' messages wait`() =
        runBlocking<Unit> {
            answer = Result.Error(SendDirectMessageError.RecipientDeleted)
            waiting = Result.Success(ListDirectMessagesResponse(messages = listOf(messageFrom(BOB))))

            sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

            assertEquals(listOf(ALICE), forgotten)
        }

    /**
     * What Alice sent before her account went opens only in the groups forgetting her deletes, so
     * she stays until it is read: by her notice of the deletion among it, or by the next message to
     * her.
     * The caller is told she is gone all the same, and drops what it kept for her.
     */
    @Test
    fun `a contact who deleted their account is kept while something they sent is waiting`() = runBlocking<Unit> {
        answer = Result.Error(SendDirectMessageError.RecipientDeleted)
        waiting = Result.Success(ListDirectMessagesResponse(messages = listOf(messageFrom(BOB), messageFrom(ALICE))))

        val sent = sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

        assertEquals(Result.Error(DirectSendError.RecipientDeleted), sent)
        assertTrue(forgotten.isEmpty())

        // Read since, and acknowledged.
        waiting = Result.Success(ListDirectMessagesResponse(messages = emptyList()))

        sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

        assertEquals(listOf(ALICE), forgotten)
    }

    /**
     * What waits is listed a page at a time and read whole, see `DirectMessageApi.getDirectMessages`:
     * Alice's message on the last page keeps her as surely as on the first.
     */
    @Test
    fun `a contact who deleted their account is kept while what they sent waits on a later page`() =
        runBlocking<Unit> {
            val listed = listOf(messageFrom(BOB), messageFrom(BOB), messageFrom(ALICE))
            val paged = DirectMessageSender(
                ensureChannel = { Result.Success(DirectChannel(mlsGroupId = byteArrayOf(1, 2, 3), welcome = null)) },
                noteWelcomeAttached = { },
                encrypt = { _, payload -> Result.Success(payload) },
                post = { answer },
                listWaiting = {
                    readWholeListing { cursor -> Result.Success(pageOf(items = listed, cursor = cursor, perPage = 2)) }
                        .map { ListDirectMessagesResponse(messages = it) }
                },
                receiveAccountDeletion = { userUuid -> forgotten += userUuid },
            )
            answer = Result.Error(SendDirectMessageError.RecipientDeleted)

            val sent = paged.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

            assertEquals(Result.Error(DirectSendError.RecipientDeleted), sent)
            assertTrue(forgotten.isEmpty())
        }

    /** Anything of hers could be waiting, so she is left to the next message to her. */
    @Test
    fun `a contact who deleted their account is kept when the server cannot say what is waiting`() =
        runBlocking<Unit> {
            answer = Result.Error(SendDirectMessageError.RecipientDeleted)
            waiting = Result.Error(NetworkError.REQUEST_TIMEOUT)

            val sent = sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

            assertEquals(Result.Error(DirectSendError.RecipientDeleted), sent)
            assertTrue(forgotten.isEmpty())
        }

    /**
     * A recipient the server knows nothing of, or the proxy in front of the API while the API is
     * being redeployed: neither says Alice deleted her account, so she stays, and the caller reads
     * a failure like any other.
     */
    @Test
    fun `a 404 forgets nobody and is a failure like any other`() = runBlocking<Unit> {
        answer = Result.Error(SendDirectMessageError.Network(NetworkError.NOT_FOUND))

        val sent = sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

        assertEquals(Result.Error(DirectSendError.Network(NetworkError.NOT_FOUND)), sent)
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun `a refusal for a limit forgets nobody and names the limit`() = runBlocking<Unit> {
        answer = Result.Error(SendDirectMessageError.RateLimited(retryAfterSeconds = 42, scope = MessageLimitScope.RECIPIENT))

        val sent = sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9))

        assertEquals(
            Result.Error(DirectSendError.RateLimited(retryAfterSeconds = 42, scope = MessageLimitScope.RECIPIENT)),
            sent,
        )
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun `a message the server takes forgets nobody`() = runBlocking<Unit> {
        assertEquals(Result.Success(Unit), sender.send(recipientUserUuid = ALICE, payload = byteArrayOf(9)))

        assertEquals(listOf(ALICE), posted.map { it.recipientUserUuid })
        assertTrue(forgotten.isEmpty())
    }

    /** The server's word on anything else, an expense naming them for instance, goes as far. */
    @Test
    fun `accounts the server says were deleted are forgotten with no message sent`() = runBlocking<Unit> {
        sender.receiveAccountDeletions(userUuids = listOf(ALICE, BOB))

        assertEquals(listOf(ALICE, BOB), forgotten)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `an account the server says was deleted is kept while something they sent is waiting`() =
        runBlocking<Unit> {
            waiting = Result.Success(ListDirectMessagesResponse(messages = listOf(messageFrom(ALICE))))

            sender.receiveAccountDeletions(userUuids = listOf(ALICE, BOB))

            assertEquals(listOf(BOB), forgotten)
        }

    @Test
    fun `accounts the server says were deleted are kept when it cannot say what is waiting`() =
        runBlocking<Unit> {
            waiting = Result.Error(NetworkError.REQUEST_TIMEOUT)

            sender.receiveAccountDeletions(userUuids = listOf(ALICE, BOB))

            assertTrue(forgotten.isEmpty())
        }

    /** The next expense refused for naming them, all that is left for a contact with no group. */
    @Test
    fun `an account kept when the server could not say what is waiting is forgotten by the next refusal`() =
        runBlocking<Unit> {
            waiting = Result.Error(NetworkError.REQUEST_TIMEOUT)
            sender.receiveAccountDeletions(userUuids = listOf(ALICE))

            waiting = Result.Success(ListDirectMessagesResponse(messages = emptyList()))
            sender.receiveAccountDeletions(userUuids = listOf(ALICE))

            assertEquals(listOf(ALICE), forgotten)
            assertTrue(posted.isEmpty())
        }
}

/** A message [senderUserUuid] sent the user, what is in it being nothing to the sender. */
private fun messageFrom(senderUserUuid: String): DirectMessageDto =
    DirectMessageDto(
        uuid = "44444444-4444-4444-4444-44444444444${senderUserUuid.first()}",
        senderUserUuid = senderUserUuid,
        ciphertext = "",
        createdAt = "2026-10-21T00:00:00Z",
    )
