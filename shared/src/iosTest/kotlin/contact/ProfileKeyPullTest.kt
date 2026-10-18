package contact

import channel.DirectChannelError
import database.data.local.dao.ContactTables
import database.data.local.dao.copy
import database.data.local.entity.ContactEntity
import database.data.local.entity.PendingProfileKeyEntity
import kotlinx.coroutines.runBlocking
import message.DirectSendError
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import util.nowEpochMilliseconds
import vault.InMemoryProfileKeyVault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "77777777-7777-7777-7777-777777777777"
private const val ERIN = "99999999-9999-9999-9999-999999999999"

private const val MINUTE = 60L * 1000
private const val HOUR = 60 * MINUTE

/**
 * The user asking their contacts for the keys to their profiles that went missing on the way,
 * and answering the contacts who ask for theirs.
 */
class ProfileKeyPullTest {

    private val tables = ContactTables()

    private val userKey = generateSymmetricEncryptionKey()
    private val vault = InMemoryProfileKeyVault(profileEncryptionKey = userKey)

    private val requests = RequestsSent()
    private val replies = RepliesSent()

    /** Who a reply was claimed for, and whether their budget allowed it. */
    private val claims = mutableListOf<String>()
    private var budgetAllows = true

    private val pull = ProfileKeyPull(
        pendingProfileKeyDao = tables.pendingProfileKeyDao,
        contactDao = tables.contactDao,
        profileKeyVault = vault,
        send = requests::send,
        claimReply = { peerUuid -> claims += peerUuid; budgetAllows },
        replyIn = replies::reply,
    )

    @Test
    fun `nobody missing a key sends nothing`() = runBlocking<Unit> {
        tables.add(contact(ALICE))

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys())

        assertTrue(requests.recipients.isEmpty())
    }

    /** The first try goes out at once, and what came of it is recorded for the backoff. */
    @Test
    fun `a contact whose key is missing is asked at once`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        missing(ALICE)

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys())

        assertEquals(listOf(ALICE), requests.recipients)
        assertTrue(ProfileKeyRequest.matches(plaintext = requests.payloads.single()))

        val row = tables.pending.getValue(ALICE)

        assertEquals(1, row.requestCount)
        assertNotNull(row.lastRequestedAt)
        assertNotNull(tables.budgets[ALICE])
    }

    /** Asking again is the backoff's to decide, however often the contact sync runs. */
    @Test
    fun `a contact asked a moment ago waits out the backoff`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        tables.add(contact(BOB))
        missing(ALICE, lastRequestedAt = nowEpochMilliseconds() - MINUTE, requestCount = 1)
        missing(BOB, lastRequestedAt = nowEpochMilliseconds() - 2 * HOUR, requestCount = 1)

        pull.requestMissingKeys()

        assertEquals(listOf(BOB), requests.recipients)
    }

    /** Two requests at once and one more an hour after that, whatever the backoff allows. */
    @Test
    fun `a contact whose budget is spent is left due`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        missing(ALICE)
        tables.budgets[ALICE] = nowEpochMilliseconds() + 2 * HOUR

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys())

        assertTrue(requests.recipients.isEmpty())
        assertEquals(0, tables.pending.getValue(ALICE).requestCount)
    }

    /** Never a former contact, nor one whose identity is in doubt. */
    @Test
    fun `a removed or blocked contact is not asked`() = runBlocking<Unit> {
        tables.add(contact(ALICE).copy(isRemoved = true))
        tables.add(contact(BOB))
        tables.blocked += BOB
        missing(ALICE)
        missing(BOB)

        pull.requestMissingKeys()

        assertTrue(requests.recipients.isEmpty())
    }

    /** Final for an account, and the row stays so the read that finds nothing does not ask again. */
    @Test
    fun `a contact whose account is gone is never asked again`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        missing(ALICE)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.RecipientGone)

        assertIs<Result.Success<Unit>>(pull.requestMissingKeys())

        assertTrue(tables.pending.getValue(ALICE).isAccountGone)

        tables.pendingProfileKeyDao.recordStale(userUuid = ALICE, profileEncryptionKey = byteArrayOf(1, 2, 3))
        tables.budgets.clear()
        pull.requestMissingKeys()

        assertEquals(listOf(ALICE), requests.recipients)
    }

    /** Sending more only pushes the count further past the limit: the rest wait, still due. */
    @Test
    fun `running out of the allowance of messages stops the pass`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        tables.add(contact(BOB))
        missing(ALICE)
        missing(BOB)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.RateLimited)

        val pass = pull.requestMissingKeys()

        assertEquals(
            ContactSyncError.Network(NetworkError.TOO_MANY_REQUESTS),
            assertIs<Result.Error<ContactSyncError>>(pass).error,
        )
        assertEquals(listOf(ALICE), requests.recipients)
        assertNull(tables.pending.getValue(ALICE).lastRequestedAt)
        assertNull(tables.pending.getValue(BOB).lastRequestedAt)
    }

    /**
     * No channel to them, somebody unpinned or in doubt: a try all the same, which waits out the
     * backoff, and nothing to report. Anything else is reported, and waits the same way.
     *
     * A request that timed out may have been stored and delivered all the same, so it is charged
     * to the contact's budget, unlike one that never left this device.
     */
    @Test
    fun `any other failure backs off`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        tables.add(contact(BOB))
        missing(ALICE)
        missing(BOB)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.Channel(DirectChannelError.Unreachable))
        requests.outcomes[BOB] = Result.Error(DirectSendError.Network(NetworkError.REQUEST_TIMEOUT))

        val pass = pull.requestMissingKeys()

        assertEquals(
            ContactSyncError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<ContactSyncError>>(pass).error,
        )
        assertEquals(1, tables.pending.getValue(ALICE).requestCount)
        assertEquals(1, tables.pending.getValue(BOB).requestCount)
        assertFalse(tables.pending.getValue(ALICE).isAccountGone)
        assertTrue(assertNotNull(tables.budgets[ALICE]) <= nowEpochMilliseconds())
        assertTrue(assertNotNull(tables.budgets[BOB]) > nowEpochMilliseconds())
    }

    /** One that never left this device spends nothing of a contact it cannot reach yet. */
    @Test
    fun `a request that never left the device gives the budget back`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        missing(ALICE)
        requests.outcomes[ALICE] = Result.Error(DirectSendError.Channel(DirectChannelError.NotPinned))

        pull.requestMissingKeys()

        assertTrue(assertNotNull(tables.budgets[ALICE]) <= nowEpochMilliseconds())
    }

    /** Back from a log-out, so not everyone at once. */
    @Test
    fun `a pass asks three contacts at most`() = runBlocking<Unit> {
        listOf(ALICE, BOB, CAROL, DAVE, ERIN).forEach {
            tables.add(contact(it))
            missing(it)
        }

        pull.requestMissingKeys()

        assertEquals(listOf(ALICE, BOB, CAROL), requests.recipients)
        assertNull(tables.pending.getValue(DAVE).lastRequestedAt)
    }

    /**
     * In the group the request was read in, with the key the vault holds now, which the contact
     * is then recorded as holding, so the next push does not hand it to them again.
     */
    @Test
    fun `a contact who asks is handed the current key`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        val group = byteArrayOf(4, 5, 6)

        pull.answer(requesterUuid = ALICE, mlsGroupId = group)

        assertEquals(listOf(ALICE), claims)

        val reply = replies.sent.single()

        assertEquals(ALICE, reply.recipientUserUuid)
        assertContentEquals(group, reply.mlsGroupId)
        assertContentEquals(userKey, assertNotNull(ProfileKeyUpdate.parse(plaintext = reply.payload)).profileEncryptionKey)
        assertEquals(profileEncryptionKeyPartialHash(userKey), tables.contacts.getValue(ALICE).handedProfileKeyHash)
    }

    /**
     * Somebody removed holds an older key, and this device hands them nothing: what the server's
     * read tells them, that a newer version exists, is all they learn of it.
     */
    @Test
    fun `nobody but a contact is answered or spends a reply`() = runBlocking<Unit> {
        tables.add(contact(ALICE).copy(isRemoved = true))
        tables.add(contact(BOB))
        tables.blocked += BOB

        listOf(ALICE, BOB, CAROL).forEach { pull.answer(requesterUuid = it, mlsGroupId = byteArrayOf(4, 5, 6)) }

        assertTrue(claims.isEmpty())
        assertTrue(replies.sent.isEmpty())
        assertEquals(0, vault.reads)
    }

    /**
     * Dropped the way a stranger's is: an honest contact asks again on their own backoff. The
     * reply is claimed before the key is read, so asking past the budget costs no vault read.
     */
    @Test
    fun `a contact past their budget of replies is not answered`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        budgetAllows = false

        pull.answer(requesterUuid = ALICE, mlsGroupId = byteArrayOf(4, 5, 6))

        assertEquals(listOf(ALICE), claims)
        assertTrue(replies.sent.isEmpty())
        assertNull(tables.contacts.getValue(ALICE).handedProfileKeyHash)
        assertEquals(0, vault.reads)
    }

    /** Not recorded as holding a key that never reached them: the next push hands it. */
    @Test
    fun `an answer that does not go out records nothing`() = runBlocking<Unit> {
        tables.add(contact(ALICE))
        replies.outcome = Result.Error(DirectSendError.Network(NetworkError.REQUEST_TIMEOUT))

        pull.answer(requesterUuid = ALICE, mlsGroupId = byteArrayOf(4, 5, 6))

        assertNull(tables.contacts.getValue(ALICE).handedProfileKeyHash)
    }

    private fun contact(userUuid: String) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            handedProfileKeyHash = null,
            isBackedUp = true,
        )

    private fun missing(userUuid: String, lastRequestedAt: Long? = null, requestCount: Int = 0) {
        tables.pending[userUuid] = PendingProfileKeyEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            lastRequestedAt = lastRequestedAt,
            requestCount = requestCount,
        )
    }
}

/** Sends nothing anywhere, answering each recipient as told and every other one with success. */
private class RequestsSent {
    val outcomes = mutableMapOf<String, EmptyResult<DirectSendError>>()

    val recipients = mutableListOf<String>()
    val payloads = mutableListOf<ByteArray>()

    suspend fun send(recipientUserUuid: String, payload: ByteArray): EmptyResult<DirectSendError> {
        recipients += recipientUserUuid
        payloads += payload

        return outcomes[recipientUserUuid] ?: Result.Success(Unit)
    }
}

private class SentReply(val recipientUserUuid: String, val mlsGroupId: ByteArray, val payload: ByteArray)

/** Replies nowhere, with [outcome], keeping what each one was. */
private class RepliesSent {
    var outcome: EmptyResult<DirectSendError> = Result.Success(Unit)

    val sent = mutableListOf<SentReply>()

    suspend fun reply(recipientUserUuid: String, mlsGroupId: ByteArray, payload: ByteArray): EmptyResult<DirectSendError> {
        sent += SentReply(recipientUserUuid = recipientUserUuid, mlsGroupId = mlsGroupId, payload = payload)

        return outcome
    }
}
