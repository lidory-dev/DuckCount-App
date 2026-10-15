package contact

import channel.ChannelRead
import channel.readIn
import database.data.local.entity.DirectChannelEntity
import expense.ExpectedKeys
import expense.ExpenseKey
import expense.ExpenseKeyShare
import expense.recordsFrom
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.ApplicationMessage
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newOneTimeExpenseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "01234567-89ab-4cde-8f01-23456789abcd"
private const val BOB = "11111111-1111-1111-1111-111111111111"

/**
 * What Bob makes of a key share from Alice, whom he sent a contact request to, read in the
 * group the request created: sent and opened through the library itself, as
 * `ContactRequestRepository` sends and opens one.
 */
class UnansweredRequestTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())

    /** The group Bob's request created, which Alice joined opening it, pinning nobody. */
    private val requestGroup: ByteArray = run {
        val keyPackage = alice.generateKeyPackages(oneTimeCount = 1u).oneTimeKeyPackages.single()

        val request = bob.createGroup(
            invitees = listOf(InviteeKeyPackage(userUuid = ALICE, keyPackage = keyPackage)),
        )

        alice.joinGroup(
            welcome = request.welcomes.single().welcome,
            expectedMembers = listOf(BOB),
            pinMembers = false,
        )

        request.mlsGroupId
    }

    /** An expense of Alice's naming Bob, which the server takes from anybody. */
    private val expense = assertNotNull(newOneTimeExpenseId(creditorUuid = ALICE))

    @Test
    fun `a key share she sends before answering is read but makes nothing of the group or the key`() {
        val read = bobReads(aliceSendsShare())

        // Pinned when the request went out, so the pin is no bar to her.
        assertTrue(read.isSenderPinned)

        assertEquals(ChannelRead.UNCHANGED, readIn(channel = null, mlsGroupId = requestGroup, isContact = false))
        assertTrue(keptOf(read, expected = ExpectedKeys(isContact = false, awaitedAtRemoval = emptySet())).isEmpty())
    }

    @Test
    fun `once her answer made the group the channel her key share is kept`() {
        // As `ContactRequestRepository.receiveAnswer` leaves Bob: Alice a contact, and the
        // request's group the channel to her.
        val channel = DirectChannelEntity(
            peerUuid = ALICE,
            mlsGroupId = requestGroup,
            previousMlsGroupId = null,
            olderMlsGroupId = null,
            pendingWelcome = null,
            pendingWelcomeSince = null,
            replyBudgetFullAt = null,
        )

        val read = bobReads(aliceSendsShare())

        assertEquals(ChannelRead.JOINED, readIn(channel = channel, mlsGroupId = requestGroup, isContact = true))

        val kept = keptOf(read, expected = ExpectedKeys(isContact = true, awaitedAtRemoval = emptySet()))

        assertEquals(listOf(expense.uuid), kept.map { it.expenseUuid })
    }

    private fun aliceSendsShare(): ByteArray {
        val share = ExpenseKeyShare(
            keys = listOf(
                ExpenseKey(
                    expenseUuid = expense.uuid,
                    key = generateSymmetricEncryptionKey(),
                    nonce = expense.nonce,
                    beneficiaryUuids = listOf(BOB),
                ),
            ),
        )

        return alice.loadGroup(mlsGroupId = requestGroup).use {
            it.encryptApplicationMessage(payload = share.serialize())
        }
    }

    private fun bobReads(message: ByteArray): ApplicationMessage =
        bob.loadGroup(mlsGroupId = requestGroup).use { it.readApplicationMessage(message = message) }

    /** What Bob keeps of the share [read] carries, checked against the identity he pinned for Alice. */
    private fun keptOf(read: ApplicationMessage, expected: ExpectedKeys) =
        assertNotNull(ExpenseKeyShare.parse(plaintext = read.payload)).recordsFrom(
            senderUuid = read.senderUserUuid,
            senderSignatureKey = assertNotNull(bob.pinnedSignatureKey(userUuid = ALICE)),
            expected = expected,
        )
}
