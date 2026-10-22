package account

import contact.AccountDeletionNotice
import contact.ContactMessage
import contact.ContactRemoval
import contact.RemoveContactError
import database.data.remote.FinishAccountDeletionRequestBody
import database.data.remote.MessageLimitScope
import database.data.remote.StartAccountDeletionRequestBody
import database.data.remote.StartAccountDeletionResponse
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.ReAuthInterface
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val USER = "22222222-2222-2222-2222-222222222222"
private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"

private const val PASSWORD = "correct horse battery staple"

/**
 * The user deleting their account, with the library's re-authentication, the server and the
 * contacts stood in for. Alice, Bob and Carol are the user's contacts.
 */
class AccountDeletionTest {

    /** What reached the server, the contacts and the session, in order. */
    private val events = mutableListOf<String>()

    /** How many re-authentications were started, each one's messages naming it. */
    private var handshakes = 0

    /** What the server answers each `PUT`, in order, and success once these run out. */
    private val deletionOutcomes = ArrayDeque<Result<Unit, NetworkError>>()

    /** What the server answers each `POST`, in order, and [startOutcome] once these run out. */
    private val startOutcomes = ArrayDeque<Result<Unit, NetworkError>>()

    /** Whether the server takes the session when asked, see `DuckCountClient.isSessionAccepted`. */
    private var sessionOutcome: Result<Boolean, NetworkError> = Result.Success(true)

    /** The finalization each `PUT` carried. */
    private val finalizations = mutableListOf<String>()

    private var startOutcome: Result<Unit, NetworkError> = Result.Success(Unit)

    private val noticeOutcomes = mutableMapOf<String, EmptyResult<RemoveContactError>>()

    /** What each contact was told, by contact UUID. */
    private val notices = mutableMapOf<String, ByteArray>()

    /**
     * Whether a deletion is out, see `DuckCountClient.whileDeletionOut`. Its record is written and
     * dropped only while it is, and the session is asked about only once it no longer is: taken
     * meanwhile, it would say nothing of the deletion.
     */
    private var isDeletionOut = false

    /**
     * The record of a deletion in doubt, see `setAccountDeletionInDoubt`: written as one goes out,
     * dropped once refused, and kept from one attempt to the next.
     */
    private var inDoubt = false

    private val deletion = AccountDeletion(
        newReAuth = { HandshakeStandIn(id = ++handshakes) },
        startDeletion = ::start,
        finishDeletion = ::finish,
        isSessionAccepted = {
            assertFalse(isDeletionOut)
            events += "session asked"
            sessionOutcome
        },
        whileDeletionOut = ::whileDeletionOut,
        noteInDoubt = {
            assertTrue(isDeletionOut)
            events += "in doubt"
            inDoubt = true
        },
        clearInDoubt = {
            assertTrue(isDeletionOut)
            events += "not in doubt"
            inDoubt = false
        },
        isInDoubt = { inDoubt },
        contactUuids = { listOf(ALICE, BOB, CAROL) },
        sendNotice = { contactUuid, notice ->
            events += "notice $contactUuid"
            notices[contactUuid] = notice
            noticeOutcomes[contactUuid] ?: Result.Success(Unit)
        },
        endSession = {
            assertFalse(isDeletionOut)
            events += "end session"
        },
    )

    @Test
    fun `every contact is told before the account goes and the session ends after it`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(
            listOf("start", "notice $ALICE", "notice $BOB", "notice $CAROL", "in doubt", "finish", "end session"),
            events,
        )
        assertEquals(listOf(finalizationOf(handshake = 1)), finalizations)
    }

    /**
     * Their app reads it as the account gone rather than as a removal, and keeps the user's name
     * for the expenses the two of them share, which a removal would drop.
     */
    @Test
    fun `every contact is told of the deletion rather than of a removal`() = runBlocking<Unit> {
        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(setOf(ALICE, BOB, CAROL), notices.keys)

        notices.values.forEach { notice ->
            assertTrue(AccountDeletionNotice.matches(plaintext = notice))
            assertFalse(ContactRemoval.matches(plaintext = notice))
            assertEquals(ContactMessage.AccountDeleted, ContactMessage.read(payload = notice))
        }
    }

    /** OPAQUE turns the password down on this device, before anything would delete anything. */
    @Test
    fun `a wrong password stops everything before anybody is told`() = runBlocking<Unit> {
        val outcome = deletion.delete(userUuid = USER, password = "not it")

        assertEquals(AccountDeletionError.WrongPassword, assertIs<Result.Error<AccountDeletionError>>(outcome).error)
        assertEquals(listOf("start"), events)
    }

    @Test
    fun `a re-authentication the server refuses tells nobody`() = runBlocking<Unit> {
        startOutcome = Result.Error(NetworkError.NO_INTERNET)

        val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Network(NetworkError.NO_INTERNET),
            assertIs<Result.Error<AccountDeletionError>>(outcome).error,
        )
        assertEquals(listOf("start"), events)
    }

    /** Nothing about telling a contact keeps the account from going. */
    @Test
    fun `a contact who could not be told is skipped`() = runBlocking<Unit> {
        noticeOutcomes[ALICE] = Result.Error(RemoveContactError.Network(NetworkError.REQUEST_TIMEOUT))
        noticeOutcomes[BOB] = Result.Error(RemoveContactError.Storage)

        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(
            listOf("start", "notice $ALICE", "notice $BOB", "notice $CAROL", "in doubt", "finish", "end session"),
            events,
        )
    }

    /** Which costs nothing of what the user may send Carol. */
    @Test
    fun `past what the user may send one contact the others are still told`() = runBlocking<Unit> {
        noticeOutcomes[ALICE] = Result.Error(RemoveContactError.RateLimited(scope = MessageLimitScope.RECIPIENT))

        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(
            listOf("start", "notice $ALICE", "notice $BOB", "notice $CAROL", "in doubt", "finish", "end session"),
            events,
        )
    }

    /** Nothing more would go out, and Carol is left to find the account gone. */
    @Test
    fun `past what the user may send everybody the notices stop and the account still goes`() = runBlocking<Unit> {
        noticeOutcomes[BOB] = Result.Error(RemoveContactError.RateLimited(scope = MessageLimitScope.SENDER))

        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(listOf("start", "notice $ALICE", "notice $BOB", "in doubt", "finish", "end session"), events)
    }

    /** The server keeps one for a minute, which telling the contacts took longer than. */
    @Test
    fun `a re-authentication that expired while the contacts were told is run again once`() = runBlocking<Unit> {
        deletionOutcomes += Result.Error(NetworkError.NOT_FOUND)

        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(
            listOf(
                "start",
                "notice $ALICE",
                "notice $BOB",
                "notice $CAROL",
                "in doubt",
                "finish",
                "not in doubt",
                "start",
                "in doubt",
                "finish",
                "end session",
            ),
            events,
        )
        assertEquals(listOf(finalizationOf(handshake = 1), finalizationOf(handshake = 2)), finalizations)
    }

    @Test
    fun `a re-authentication not found twice is reported and the session stays`() = runBlocking<Unit> {
        deletionOutcomes += Result.Error(NetworkError.NOT_FOUND)
        deletionOutcomes += Result.Error(NetworkError.NOT_FOUND)

        val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Network(NetworkError.NOT_FOUND),
            assertIs<Result.Error<AccountDeletionError>>(outcome).error,
        )
        assertEquals(2, finalizations.size)
        assertEquals("not in doubt", events.last())
        assertFalse("end session" in events)
    }

    /**
     * The group commits it was sent do not match the server's: refused before anything was
     * deleted, so it is in doubt no longer. It used the attempt up all the same, but a new one
     * would be refused just as well.
     */
    @Test
    fun `a deletion the server refuses is reported without trying again and the session stays`() =
        runBlocking<Unit> {
            deletionOutcomes += Result.Error(NetworkError.BAD_REQUEST)

            val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

            assertEquals(
                AccountDeletionError.Network(NetworkError.BAD_REQUEST),
                assertIs<Result.Error<AccountDeletionError>>(outcome).error,
            )
            assertEquals(1, finalizations.size)
            assertEquals(listOf("in doubt", "finish", "not in doubt"), events.takeLast(3))
            assertFalse("session asked" in events)
            assertFalse("end session" in events)
        }

    /**
     * The server deleted the account, then its answer was lost, to the timeout or to a dropped
     * connection: the long-lived token went with the account, so the session it no longer takes
     * says the deletion went through.
     */
    @Test
    fun `a deletion whose answer never came is found through and the session ends`() = runBlocking<Unit> {
        listOf(NetworkError.REQUEST_TIMEOUT, NetworkError.UNREACHABLE, NetworkError.SERVER_ERROR).forEach { lost ->
            events.clear()
            deletionOutcomes += Result.Error(lost)
            sessionOutcome = Result.Success(false)

            assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

            assertEquals(listOf("in doubt", "finish", "session asked", "end session"), events.takeLast(4))
        }
    }

    /** The server still takes the session, so the account is still there, and so is the session. */
    @Test
    fun `a deletion whose answer never came with the session still taken is reported`() = runBlocking<Unit> {
        deletionOutcomes += Result.Error(NetworkError.REQUEST_TIMEOUT)

        val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<AccountDeletionError>>(outcome).error,
        )
        assertEquals(listOf("in doubt", "finish", "session asked"), events.takeLast(3))
        assertEquals(1, finalizations.size)
    }

    /**
     * Nor could the server be asked about the session after: the user is told the account may be
     * gone, and the deletion stays in doubt, for the server turning the session down later to say
     * it went through.
     */
    @Test
    fun `a deletion whose answer never came and that could not be asked about is unconfirmed`() =
        runBlocking<Unit> {
            deletionOutcomes += Result.Error(NetworkError.UNREACHABLE)
            sessionOutcome = Result.Error(NetworkError.NO_INTERNET)

            val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

            assertEquals(
                AccountDeletionError.Unconfirmed(NetworkError.UNREACHABLE),
                assertIs<Result.Error<AccountDeletionError>>(outcome).error,
            )
            assertEquals(listOf("in doubt", "finish", "session asked"), events.takeLast(3))
            assertFalse("not in doubt" in events)
            assertFalse("end session" in events)
        }

    /**
     * Trying again after a deletion that went through without its answer: the server will not
     * start a re-authentication for an account that is gone, and no longer takes the session, so
     * the session ends with nobody told again and nothing sent to delete.
     */
    @Test
    fun `an attempt finding the account gone ends the session and tells nobody`() = runBlocking<Unit> {
        startOutcomes += Result.Error(NetworkError.NOT_FOUND)
        sessionOutcome = Result.Success(false)

        assertIs<Result.Success<Unit>>(deletion.delete(userUuid = USER, password = PASSWORD))

        assertEquals(listOf("start", "session asked", "end session"), events)
        assertEquals(emptyList(), finalizations)
    }

    /**
     * The proxy in front of the API answers a `404` for every route while the API is being
     * redeployed, and the server still takes the session: the account is still there.
     */
    @Test
    fun `a re-authentication not found with the session still taken is reported`() = runBlocking<Unit> {
        startOutcomes += Result.Error(NetworkError.NOT_FOUND)

        val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Network(NetworkError.NOT_FOUND),
            assertIs<Result.Error<AccountDeletionError>>(outcome).error,
        )
        assertEquals(listOf("start", "session asked"), events)
    }

    /**
     * Trying again after a deletion that could not be asked about: the server will not start a
     * re-authentication for an account that is gone, and still cannot be asked about the session.
     * The deletion is still in doubt, so the user is told again the account may be gone, not that
     * it could not be deleted.
     */
    @Test
    fun `a retry finding no account after an unconfirmed deletion is unconfirmed again`() = runBlocking<Unit> {
        deletionOutcomes += Result.Error(NetworkError.REQUEST_TIMEOUT)
        sessionOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)

        assertEquals(
            AccountDeletionError.Unconfirmed(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<AccountDeletionError>>(deletion.delete(userUuid = USER, password = PASSWORD)).error,
        )

        events.clear()
        startOutcomes += Result.Error(NetworkError.NOT_FOUND)

        val retry = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Unconfirmed(NetworkError.NOT_FOUND),
            assertIs<Result.Error<AccountDeletionError>>(retry).error,
        )
        assertEquals(listOf("start", "session asked"), events)
        assertTrue(inDoubt)
    }

    /**
     * Nothing went out that would have deleted the account, so the `404` is likelier the proxy's,
     * and is reported as it is.
     */
    @Test
    fun `a re-authentication not found that could not be asked about with nothing in doubt is reported`() =
        runBlocking<Unit> {
            startOutcomes += Result.Error(NetworkError.NOT_FOUND)
            sessionOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)

            val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

            assertEquals(
                AccountDeletionError.Network(NetworkError.NOT_FOUND),
                assertIs<Result.Error<AccountDeletionError>>(outcome).error,
            )
            assertEquals(listOf("start", "session asked"), events)
        }

    /**
     * The deletion before the second re-authentication was refused, for the first having expired,
     * which dropped its record: nothing is in doubt, so its `404` is reported as it is.
     */
    @Test
    fun `a second re-authentication not found that could not be asked about is reported`() = runBlocking<Unit> {
        deletionOutcomes += Result.Error(NetworkError.NOT_FOUND)
        startOutcomes += Result.Success(Unit)
        startOutcomes += Result.Error(NetworkError.NOT_FOUND)
        sessionOutcome = Result.Error(NetworkError.UNAUTHORIZED)

        val outcome = deletion.delete(userUuid = USER, password = PASSWORD)

        assertEquals(
            AccountDeletionError.Network(NetworkError.NOT_FOUND),
            assertIs<Result.Error<AccountDeletionError>>(outcome).error,
        )
        assertEquals(listOf("in doubt", "finish", "not in doubt", "start", "session asked"), events.takeLast(5))
        assertFalse(inDoubt)
    }

    /** Whatever answered is not the server's re-authentication. */
    @Test
    fun `a server half that is not Base64 is refused before anybody is told`() = runBlocking<Unit> {
        val misanswered = AccountDeletion(
            newReAuth = { HandshakeStandIn(id = 1) },
            startDeletion = { _, _ -> Result.Success(StartAccountDeletionResponse(credentialResponseBytes = "not base64!")) },
            finishDeletion = ::finish,
            isSessionAccepted = { error("Not used here") },
            whileDeletionOut = ::whileDeletionOut,
            noteInDoubt = { events += "in doubt" },
            clearInDoubt = { events += "not in doubt" },
            isInDoubt = { error("Not used here") },
            contactUuids = { listOf(ALICE) },
            sendNotice = { contactUuid, _ ->
                events += "notice $contactUuid"
                Result.Success(Unit)
            },
            endSession = { events += "end session" },
        )

        val outcome = misanswered.delete(userUuid = USER, password = PASSWORD)

        assertEquals(AccountDeletionError.Crypto, assertIs<Result.Error<AccountDeletionError>>(outcome).error)
        assertEquals(emptyList(), events)
    }

    private suspend fun start(
        userUuid: String,
        body: StartAccountDeletionRequestBody,
    ): Result<StartAccountDeletionResponse, NetworkError> {
        assertEquals(USER, userUuid)

        events += "start"

        return when (val outcome = startOutcomes.removeFirstOrNull() ?: startOutcome) {
            // The server's half names the handshake the request came from.
            is Result.Success -> Result.Success(StartAccountDeletionResponse(credentialResponseBytes = body.credentialRequestBytes))
            is Result.Error -> outcome
        }
    }

    private suspend fun whileDeletionOut(deletion: suspend () -> Result<Unit, NetworkError>): Result<Unit, NetworkError> {
        assertFalse(isDeletionOut)

        isDeletionOut = true

        return try {
            deletion()
        } finally {
            isDeletionOut = false
        }
    }

    private suspend fun finish(userUuid: String, body: FinishAccountDeletionRequestBody): Result<Unit, NetworkError> {
        assertEquals(USER, userUuid)
        assertEquals(emptyList(), body.groupCommits)
        assertTrue(isDeletionOut)

        events += "finish"
        finalizations += body.credentialFinalizationBytes

        return deletionOutcomes.removeFirstOrNull() ?: Result.Success(Unit)
    }
}

/** The finalization the stand-in for the re-authentication numbered [handshake] finishes with. */
private fun finalizationOf(handshake: Int): String = Base64.Default.encode(byteArrayOf(handshake.toByte(), FINISHED))

private const val FINISHED: Byte = 42

/**
 * Stands in for the library's re-authentication numbered [id]: its request names it, and it
 * finishes only with [PASSWORD], as OPAQUE turns any other down.
 */
private class HandshakeStandIn(private val id: Int) : ReAuthInterface {

    override fun start(password: String): ByteArray = byteArrayOf(id.toByte())

    override fun finish(credentialResponseBytes: ByteArray, password: String): ByteArray {
        if (password != PASSWORD) throw AuthenticationException.ClientLoginFinalizationFailed("wrong password")

        return credentialResponseBytes + FINISHED
    }
}
