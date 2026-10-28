package message

import contact.ContactSyncError
import database.data.remote.DirectMessageDto
import database.data.remote.ListDirectMessagesResponse
import kotlinx.coroutines.runBlocking
import mls.ApplicationMessageReading
import mls.InMemoryMlsStore
import mls.encryptApplicationMessage
import mls.inviteeKeyPackage
import mls.readApplicationMessage
import networking.pageOf
import networking.readWholeListing
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import util.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * What Bob's sync of his direct messages makes of one Alice, his contact, sent naming a group she
 * created with him and Carol: read in it, it would be a message from a pinned contact in a group
 * this device holds, and the group would become the channel to her. Through the library itself,
 * with the server and everything this device stores stood in for.
 */
class DirectMessageSyncTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    /** The group Alice created, which Bob joined as the group sync joins one, pinned to her. */
    private val group: ByteArray = run {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())

        val creation = alice.createGroup(
            groupUuid = GROUP,
            invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
            welcomePayload = generateSymmetricEncryptionKey(),
        )

        bob.joinGroup(
            welcome = creation.welcome,
            expectedGroupUuid = GROUP,
            expectedMembers = listOf(ALICE, CAROL),
            pinMembers = false,
        )
    }

    private val acknowledged = mutableListOf<String>()

    /** What was offered to the handlers, and in which groups reads and adoptions were noted. */
    private val offered = mutableListOf<String>()
    private val notedReads = mutableListOf<ByteArray>()
    private val adopted = mutableListOf<ByteArray>()

    private val handler = object : DirectMessageHandler {
        override suspend fun handle(
            senderUserUuid: String,
            mlsGroupId: ByteArray,
            payload: ByteArray,
        ): DirectMessageHandling {
            offered += payload.decodeToString()

            return DirectMessageHandling.HANDLED
        }
    }

    @Test
    fun `a direct message naming one of the user's groups is acknowledged unread and adopts nothing`() =
        runBlocking<Unit> {
            val message = aliceSends()

            assertIs<Result.Success<Unit>>(syncWith(groups = listOf(group), message = message).sync())

            assertEquals(listOf("m1"), acknowledged)
            assertTrue(offered.isEmpty())
            assertTrue(notedReads.isEmpty())
            assertTrue(adopted.isEmpty())

            // Its key not spent: it was never read.
            assertIs<ApplicationMessageReading.Read>(
                bob.readApplicationMessage(mlsGroupId = group, message = message),
            )
        }

    /** What the check above stands between: the same message read, offered, and noted in the group. */
    @Test
    fun `the same direct message is read and offered when the group is none of the user's`() =
        runBlocking<Unit> {
            assertIs<Result.Success<Unit>>(syncWith(groups = emptyList(), message = aliceSends()).sync())

            assertEquals(listOf("m1"), acknowledged)
            assertEquals(listOf("hello"), offered)
            assertTrue(notedReads.single().contentEquals(group))
        }

    /**
     * Alice's first message has to be read again, its acceptance not yet completed, and her next
     * one is on the next page of what is listed: she is left alone for the rest of the pass all the
     * same, which is what keeps her messages in order. Carol's, on the same page, is read.
     */
    @Test
    fun `a sender left for the pass on one page is left on the next`() = runBlocking<Unit> {
        val envelope = DirectMessageEnvelope(mlsGroupId = ByteArray(32) { 5 }, message = byteArrayOf(1)).serialize()
        val listed = listOf(
            DirectMessageDto(uuid = "m1", senderUserUuid = ALICE, ciphertext = envelope, createdAt = "2026-10-23T12:00:00Z"),
            DirectMessageDto(uuid = "m2", senderUserUuid = ALICE, ciphertext = envelope, createdAt = "2026-10-23T12:01:00Z"),
            DirectMessageDto(uuid = "m3", senderUserUuid = CAROL, ciphertext = "not an envelope", createdAt = "2026-10-23T12:02:00Z"),
        )
        val completed = mutableListOf<String>()

        val sync = DirectMessageSync(
            restoreContacts = { Result.Success(Unit) },
            listMessages = {
                readWholeListing { cursor -> Result.Success(pageOf(items = listed, cursor = cursor, perPage = 1)) }
                    .map { ListDirectMessagesResponse(messages = it) }
            },
            acknowledge = { body ->
                acknowledged += body.messageUuids
                Result.Success(Unit)
            },
            client = { Result.Success(bob) },
            isBlocked = { false },
            isGroup = { false },
            completeAcceptanceIn = { _, senderUserUuid, _ ->
                completed += senderUserUuid
                Result.Error(ContactSyncError.Network(NetworkError.NO_INTERNET))
            },
            isKnownPeer = { true },
            adopt = { _, _, mlsGroupId -> adopted += mlsGroupId },
            noteReadIn = { _, _, mlsGroupId -> notedReads += mlsGroupId },
            claimReply = { _, _ -> false },
            replyIn = { _, _, _ -> },
            handlers = listOf(handler),
        )

        val pass = sync.sync()

        assertEquals(
            DirectMessageSyncError.Contacts(ContactSyncError.Network(NetworkError.NO_INTERNET)),
            assertIs<Result.Error<DirectMessageSyncError>>(pass).error,
        )
        assertEquals(listOf(ALICE), completed)
        assertEquals(listOf("m3"), acknowledged)
        assertTrue(offered.isEmpty())
    }

    /** Alice's message in her group, as an application message she encrypted there. */
    private suspend fun aliceSends(): ByteArray =
        alice.encryptApplicationMessage(mlsGroupId = group, payload = "hello".encodeToByteArray())

    /** Bob's sync, with [message] waiting for him from Alice, naming [group], and [groups] his own. */
    private fun syncWith(groups: List<ByteArray>, message: ByteArray) = DirectMessageSync(
        restoreContacts = { Result.Success(Unit) },
        listMessages = {
            Result.Success(
                ListDirectMessagesResponse(
                    messages = listOf(
                        DirectMessageDto(
                            uuid = "m1",
                            senderUserUuid = ALICE,
                            ciphertext = DirectMessageEnvelope(mlsGroupId = group, message = message).serialize(),
                            createdAt = "2026-10-23T12:00:00Z",
                        ),
                    ),
                ),
            )
        },
        acknowledge = { body ->
            acknowledged += body.messageUuids
            Result.Success(Unit)
        },
        client = { Result.Success(bob) },
        isBlocked = { false },
        isGroup = { mlsGroupId -> groups.any { it.contentEquals(mlsGroupId) } },
        completeAcceptanceIn = { _, _, _ -> Result.Success(Unit) },
        isKnownPeer = { true },
        adopt = { _, _, mlsGroupId -> adopted += mlsGroupId },
        noteReadIn = { _, _, mlsGroupId -> notedReads += mlsGroupId },
        claimReply = { _, _ -> false },
        replyIn = { _, _, _ -> },
        handlers = listOf(handler),
    )
}
