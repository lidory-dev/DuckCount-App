package account

import authenticationUtils.clearAccountDeletionInDoubt
import authenticationUtils.isAccountDeletionInDoubt
import authenticationUtils.setAccountDeletionInDoubt
import contact.AccountDeletionNotice
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.remote.FinishAccountDeletionRequestBody
import database.data.remote.MessageLimitScope
import database.data.remote.StartAccountDeletionRequestBody
import database.data.remote.StartAccountDeletionResponse
import database.data.remote.UserApi
import database.data.repository.ContactRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import networking.DuckCountClient
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.ReAuth
import uniffi.lib_duckcount.ReAuthInterface
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64

/**
 * Deletes the user's account, once they have typed their password again.
 *
 * In this order:
 * 1. The password is checked, by the re-authentication the server asks for, see
 *    [reAuthenticate]. A wrong one stops everything here, before anybody is told anything.
 * 2. The contacts are told the user deleted their account, the ones this device holds a group
 *    with and at best, see [tellContacts], since the server tells nobody of a deletion.
 * 3. The account goes, everything the server holds of it but the expenses, and the session ends
 *    on this device as a log-out ends it, see `DuckCountClient.endSession`. Nothing authenticated
 *    is sent after: the server dropped the push subscription with the account, and the platform's
 *    own side goes once the app is up again with nobody signed in, as after a log-out.
 *
 * The contacts are told before the account goes, while the user can still send to them, so a
 * deletion that then fails leaves contacts who dropped the user, keeping only the user's name as
 * for an account gone, until they are contacts again, see [ContactBook.with][vault.ContactBook.with].
 * Trying again sees it through.
 *
 * The server deletes the account before it answers, so a deletion whose answer never comes back
 * may have gone through: what became of it is asked of the server straight away, see [settle].
 * Should that go unanswered too, the attempt after finds the account gone, see [delete], or says
 * again that it may be, and the server turning the session down later tells the user their
 * account was deleted rather than that their session expired, see [setAccountDeletionInDoubt].
 *
 * [newReAuth] makes the library's [ReAuth], [startDeletion] and [finishDeletion] are
 * [UserApi.startAccountDeletion] and [UserApi.finishAccountDeletion], [isSessionAccepted] is
 * [DuckCountClient.isSessionAccepted], [whileDeletionOut] is [DuckCountClient.whileDeletionOut],
 * [noteInDoubt], [clearInDoubt] and [isInDoubt] are [setAccountDeletionInDoubt],
 * [clearAccountDeletionInDoubt] and [isAccountDeletionInDoubt], [contactUuids] is
 * [ContactDao.getUserUuids], [sendNotice] is [ContactRepository.sendNotice] and [endSession] is
 * [DuckCountClient.endSession], or what a test stands in for any of them.
 */
class AccountDeletion internal constructor(
    private val newReAuth: () -> ReAuthInterface,
    private val startDeletion: suspend (
        userUuid: String,
        body: StartAccountDeletionRequestBody,
    ) -> Result<StartAccountDeletionResponse, NetworkError>,
    private val finishDeletion: suspend (
        userUuid: String,
        body: FinishAccountDeletionRequestBody,
    ) -> Result<Unit, NetworkError>,
    private val isSessionAccepted: suspend () -> Result<Boolean, NetworkError>,
    private val whileDeletionOut: suspend (
        deletion: suspend () -> Result<Unit, NetworkError>,
    ) -> Result<Unit, NetworkError>,
    private val noteInDoubt: suspend () -> Unit,
    private val clearInDoubt: () -> Unit,
    private val isInDoubt: suspend () -> Boolean,
    private val contactUuids: suspend () -> List<String>,
    private val sendNotice: suspend (contactUuid: String, notice: ByteArray) -> EmptyResult<RemoveContactError>,
    private val endSession: suspend () -> Unit,
) {
    constructor(
        userApi: UserApi,
        contactDao: ContactDao,
        contactRepository: ContactRepository,
        duckCountClient: DuckCountClient,
    ) : this(
        newReAuth = { ReAuth() },
        startDeletion = userApi::startAccountDeletion,
        finishDeletion = userApi::finishAccountDeletion,
        isSessionAccepted = duckCountClient::isSessionAccepted,
        whileDeletionOut = { deletion -> duckCountClient.whileDeletionOut(deletion) },
        noteInDoubt = ::setAccountDeletionInDoubt,
        clearInDoubt = ::clearAccountDeletionInDoubt,
        isInDoubt = {
            try {
                isAccountDeletionInDoubt()
            } catch (exception: CancellationException) {
                // The caller going away, which the catch below would otherwise take for a locked
                // device, a CancellationException being an IllegalStateException.
                throw exception
            } catch (_: IllegalStateException) {
                // Sealed against a locked device, which this one may have become since the user
                // tapped, while the contacts were told for instance: read as none, see settleStart.
                false
            }
        },
        contactUuids = contactDao::getUserUuids,
        sendNotice = contactRepository::sendNotice,
        endSession = duckCountClient::endSession,
    )

    /**
     * Deletes the account of [userUuid], the user signed in, once [password] proves it is
     * theirs, then ends the session. The caller restarts the process once this succeeds, as after
     * a log-out, see `DuckCountClient.endSession`.
     *
     * The server keeps a re-authentication for a minute, and telling every contact can take longer
     * on a slow network. One that expired in the meantime is run again, once.
     *
     * A re-authentication the server will not start, answering `404` for the account it would
     * delete being gone, may follow a deletion whose answer never came back and which went
     * through after all: the session then ends as after any deletion, see [settleStart]. That
     * `404` is also the one the proxy in front of the API answers every route with while the API
     * is being redeployed, which is why the server is asked about the session before anything is
     * ended, and why the user is told the account may be gone, when it cannot be asked, only while
     * a deletion of theirs is in doubt.
     *
     * Not cancellable once the deletion is sent: the server may have deleted the account by then,
     * and a session left on this device would be one of an account that is gone.
     */
    suspend fun delete(userUuid: String, password: String): EmptyResult<AccountDeletionError> {
        val proof = when (val verified = reAuthenticate(userUuid = userUuid, password = password)) {
            is Result.Success -> verified.data
            is Result.Error -> return settleStart(error = verified.error)
        }

        tellContacts()

        return withContext(NonCancellable) {
            var deletion = finish(userUuid = userUuid, proof = proof)

            // An attempt that expired while the contacts were being told. Any other failure used
            // the attempt up as well, but for a reason a new one would not change.
            if (deletion == Result.Error(NetworkError.NOT_FOUND)) {
                val renewed = when (val verified = reAuthenticate(userUuid = userUuid, password = password)) {
                    is Result.Success -> verified.data
                    is Result.Error -> return@withContext settleStart(error = verified.error)
                }

                deletion = finish(userUuid = userUuid, proof = renewed)
            }

            when (deletion) {
                is Result.Success -> ended()

                is Result.Error ->
                    if (deletion.error.leavesDeletionOpen()) {
                        settle(
                            error = AccountDeletionError.Network(deletion.error),
                            unanswered = { AccountDeletionError.Unconfirmed(deletion.error) },
                        )
                    } else {
                        Result.Error(AccountDeletionError.Network(deletion.error))
                    }
            }
        }
    }

    /**
     * Sends the deletion [proof] finishes the re-authentication with, recorded as in doubt until
     * an answer says the account is still there, see [setAccountDeletionInDoubt]: from the moment
     * it goes out, the server may delete the account without this device hearing of it.
     *
     * The server refusing it outright says so, see [leavesDeletionOpen], and so does it taking
     * the session after, see [isSessionAccepted]. A deletion that went through ends the session,
     * which drops the record with everything else.
     *
     * Out from before the record is written until its answer, or the failure in its place, is in,
     * see [DuckCountClient.whileDeletionOut]: the session taken meanwhile says nothing of it, the
     * refresh a `401` to the deletion itself sets off included, after which it is sent again. Taken
     * after, as [settle] asks, it does.
     */
    private suspend fun finish(userUuid: String, proof: String): Result<Unit, NetworkError> = whileDeletionOut {
        noteInDoubt()

        val deletion = finishDeletion(userUuid, FinishAccountDeletionRequestBody(credentialFinalizationBytes = proof))

        if (deletion is Result.Error && !deletion.error.leavesDeletionOpen()) clearInDoubt()

        deletion
    }

    /**
     * What a re-authentication that would not start comes to, [error] being why: a `404` can be
     * the account found gone, a deletion whose answer never came back having gone through, see
     * [delete], and is [settle]d. Anything else is reported as it is.
     *
     * Unanswered, the `404` is [AccountDeletionError.Unconfirmed] while a deletion this device
     * sent is still in doubt, see [setAccountDeletionInDoubt]: the server has just said there is
     * no such account, which that deletion going through would explain. With none in doubt,
     * nothing went out that would have deleted the account, the `404` is likelier the proxy's,
     * and it is reported as it is. None is by the second re-authentication [delete] runs: the
     * deletion before it was turned down, which dropped its record, see [finish].
     */
    private suspend fun settleStart(error: AccountDeletionError): EmptyResult<AccountDeletionError> =
        if (error == AccountDeletionError.Network(NetworkError.NOT_FOUND)) {
            settle(
                error = error,
                unanswered = {
                    if (isInDoubt()) AccountDeletionError.Unconfirmed(NetworkError.NOT_FOUND) else error
                },
            )
        } else {
            Result.Error(error)
        }

    /**
     * Finds out whether the account is gone after all, from whether the server still takes the
     * session, see [isSessionAccepted]: the long-lived token goes with the account. Gone, the
     * session ends as after any deletion; still there, [error] is reported; and what [unanswered]
     * says when the server could not be asked.
     *
     * A token turned down is taken for the account gone only after a failure that leaves open
     * that it is: the token also goes with a login on another device, which this cannot tell
     * from a deletion, the likelier of the two right after one may have gone through.
     *
     * A deletion the server is still running when asked, held up for longer than the timeout,
     * can find the session still taken and go through all the same. The attempt after then
     * finds the account gone, see [settleStart].
     */
    private suspend fun settle(
        error: AccountDeletionError,
        unanswered: suspend () -> AccountDeletionError,
    ): EmptyResult<AccountDeletionError> =
        when (val asked = isSessionAccepted()) {
            is Result.Success -> if (asked.data) Result.Error(error) else ended()
            is Result.Error -> Result.Error(unanswered())
        }

    /** Ends the session of an account that is gone, see `DuckCountClient.endSession`. */
    private suspend fun ended(): EmptyResult<AccountDeletionError> {
        endSession()

        return Result.Success(Unit)
    }

    /**
     * Runs the re-authentication the server asks for before it deletes the account of [userUuid],
     * an OPAQUE login with [password], and returns the message finishing it, Base64 encoded, for
     * [finishDeletion] to send.
     *
     * The library checks the password here, on this device, so a wrong one is found out before
     * anything is sent that would delete anything.
     */
    private suspend fun reAuthenticate(userUuid: String, password: String): Result<String, AccountDeletionError> =
        try {
            val reAuth = newReAuth()

            // Off the caller's thread, which may be the main one: finishing derives a key from
            // the password with Argon2.
            val request = withContext(Dispatchers.Default) { reAuth.start(password) }

            val started = startDeletion(
                userUuid,
                StartAccountDeletionRequestBody(credentialRequestBytes = Base64.Default.encode(request)),
            )

            when (started) {
                is Result.Success -> {
                    val finalization = withContext(Dispatchers.Default) {
                        reAuth.finish(
                            credentialResponseBytes = Base64.Default.decode(started.data.credentialResponseBytes),
                            password = password,
                        )
                    }

                    Result.Success(Base64.Default.encode(finalization))
                }

                is Result.Error -> Result.Error(AccountDeletionError.Network(started.error))
            }
        } catch (_: AuthenticationException.ClientLoginFinalizationFailed) {
            // How OPAQUE turns down a wrong password, as it does when signing in.
            Result.Error(AccountDeletionError.WrongPassword)
        } catch (_: AuthenticationException) {
            Result.Error(AccountDeletionError.Crypto)
        } catch (_: IllegalArgumentException) {
            // The server's half that is not Base64.
            Result.Error(AccountDeletionError.Crypto)
        }

    /**
     * Tells every contact the user deleted their account, with an [AccountDeletionNotice] rather
     * than the notice a removal sends, see [ContactRepository.sendNotice]: their app drops the user
     * as on a removal, but keeps the user's name in their own vault, which a removal would drop, see
     * `ContactRepository.receiveAccountDeletion`. The account takes the user's profile with it, so
     * nothing else could tell their devices who the user was in the expenses the two of them share
     * once one of them lost its data.
     *
     * At best: nothing here keeps the account from being deleted. A contact who could not be told,
     * or whom this device holds no group with, learns of it only once their own device names the
     * user to the server, which then answers that the account was deleted, see
     * `ContactRepository.receiveAccountDeletion`: by sending something in a group it still holds
     * with the user, or by naming them on a new one-time expense or adding them to one. One whose
     * device holds no group asks for a key package first, which a deleted account has none of,
     * and keeps the user as a contact until it names them on a new one-time expense or adds them
     * to one.
     * Past what the user may send one contact, the others are still told. Past what they may send
     * everybody, nothing more would go out, and the rest are left to those odds.
     */
    private suspend fun tellContacts() {
        for (contactUuid in contactUuids()) {
            val notice = sendNotice(contactUuid, AccountDeletionNotice.serialize())

            if (notice == Result.Error(RemoveContactError.RateLimited(scope = MessageLimitScope.SENDER))) break
        }
    }
}

/**
 * Whether a deletion that failed this way may have gone through all the same. The server deletes
 * the account and only then answers, so an answer that timed out or a connection dropped before
 * it came leave it open, and so does a `5xx`, which the proxy in front of the API may return
 * after the commit. Every other failure is a refusal the server answers before deleting anything,
 * or one that kept the deletion from being sent.
 */
private fun NetworkError.leavesDeletionOpen(): Boolean =
    when (this) {
        NetworkError.REQUEST_TIMEOUT,
        NetworkError.UNREACHABLE,
        NetworkError.SERVER_ERROR,
            -> true

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.NO_INTERNET,
        NetworkError.SERIALIZATION,
        NetworkError.UNKNOWN,
            -> false
    }
