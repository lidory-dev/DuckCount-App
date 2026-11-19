package contact

import app.lidory.duckcount.usernameMaximumLength
import authentication.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.getDiscoveryUsernameFlow
import database.data.repository.BlockedUserRepository
import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import database.data.repository.UserRepository
import database.domain.BlockedUser
import database.domain.ContactRequest
import database.domain.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sync.ForegroundSync
import util.Result
import util.onError

private const val TAG = "ContactManagement"

/**
 * What the iOS contact screens run, with the same repositories and in the same order as Android's
 * `ContactCallBacksImp`, which lives in the Android app, their failures told as it tells them, see
 * [AddContactFailure]. The discovery username is reserved from the settings, see
 * [settings.SettingsManagement], as on Android: the contact screens only read it.
 *
 * Unlike Android's, nothing here takes a notification down: the app takes down what the Contacts tab
 * shows as the tab comes up, see its `Notifier`.
 */
class ContactManagement(
    private val userRepository: UserRepository,
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val contactSync: ContactSync,
    private val foregroundSync: ForegroundSync,
    private val safetyNumbers: SafetyNumbers,
    private val networkConnectivity: NetworkConnectivity,
) {

    /** Longest username the library accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int = usernameMaximumLength()

    fun authenticatedUserUuid(): String? = getAuthenticatedUserUuid()

    /**
     * The people this device holds a name for whose name contains [filter], the user among them,
     * removed contacts aside, see [UserRepository.getAllFiltered].
     */
    fun users(filter: String): Flow<List<User>> = userRepository.getAllFiltered(filter = filter)

    /** The user [uuid] as this device holds them, as it changes, see [UserRepository.get]. */
    fun user(uuid: String): Flow<User> = userRepository.get(uuid = uuid)

    /** The username the user reserved to be discoverable by, or null while they have not reserved one. */
    fun reservedUsername(): Flow<String?> = getDiscoveryUsernameFlow()

    /** How many contact requests are waiting for the user. */
    fun contactRequestCount(): Flow<Int> = contactRequestRepository.getPendingCount()

    /** The contact requests waiting for the user, oldest first. */
    fun contactRequests(): Flow<List<ContactRequest>> = contactRequestRepository.getPending()

    /** Everybody the user blocked, again whenever that changes. */
    fun blockedUsers(): Flow<List<BlockedUser>> = blockedUserRepository.getBlocked()

    /** Where the user stands with checking the contact [userUuid], see [SafetyNumbers.observe]. */
    fun verification(userUuid: String): Flow<ContactVerificationStatus> =
        safetyNumbers.observe(userUuid = userUuid).map { it.toStatus(isOnline = networkConnectivity.isOnline()) }

    /**
     * Brings the contacts and the contact requests up to date, with everything else the device
     * shows, see [ForegroundSync]. A failure is not reported: the user keeps seeing what they already
     * had, and the next refresh picks up whatever did not get done.
     */
    suspend fun refresh() {
        foregroundSync.sync()
    }

    /** Removes the contact [userUuid], once they are told, see [ContactRepository.remove]. Null once removed. */
    suspend fun removeContact(userUuid: String): RemoveContactFailure? =
        when (val removal = contactRepository.remove(userUuid = userUuid)) {
            is Result.Success -> {
                // Told and gone from this device, so a vault write failing from here on is retried
                // by the next refresh rather than reported.
                backUp(caller = "removeContact")
                null
            }

            is Result.Error -> {
                warn("removeContact: could not remove the contact: ${removal.error}")
                removal.error.toRemoveFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Blocks the contact [userUuid], removing them first, see [ContactRepository.block]. Null once blocked. */
    suspend fun blockContact(userUuid: String): RemoveContactFailure? =
        when (val blocking = contactRepository.block(userUuid = userUuid)) {
            is Result.Success -> {
                backUp(caller = "blockContact")
                null
            }

            is Result.Error -> {
                warn("blockContact: could not block the contact: ${blocking.error}")
                blocking.error.toRemoveFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Lifts the block on [userUuid], who is no contact for it, see [BlockedUserRepository.unblock]. */
    suspend fun unblock(userUuid: String) {
        blockedUserRepository.unblock(userUuid = userUuid)

        // Lifted on this device, which is what the user sees, so a vault write failing from here on
        // is retried by the next refresh rather than reported.
        backUp(caller = "unblock")
    }

    /** Marks [safetyNumber], the one the user compared with the contact [userUuid], verified, here and in the vault. */
    suspend fun markVerified(userUuid: String, safetyNumber: String) {
        safetyNumbers.markVerified(userUuid = userUuid, safetyNumber = safetyNumber)
        backUp(caller = "markVerified")
    }

    /** Clears the mark [markVerified] made for the contact [userUuid], the same way. */
    suspend fun clearVerification(userUuid: String) {
        safetyNumbers.clearMark(userUuid = userUuid)
        backUp(caller = "clearVerification")
    }

    /** Accepts the contact request [requestUuid], making its sender a contact. Null once accepted. */
    suspend fun acceptRequest(requestUuid: String): AnswerContactRequestFailure? =
        when (val acceptance = contactRequestRepository.accept(requestUuid = requestUuid)) {
            is Result.Success -> {
                // Answered and its sender stored on this device, so whatever fails from here on is
                // retried by the next refresh rather than reported.
                backUp(caller = "acceptRequest")
                null
            }

            is Result.Error -> {
                warn("acceptRequest: could not accept the request: ${acceptance.error}")
                acceptance.error.toAnswerFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Refuses the contact request [requestUuid], which its sender is not told. Null once refused. */
    suspend fun refuseRequest(requestUuid: String): AnswerContactRequestFailure? =
        when (val refusal = contactRequestRepository.refuse(requestUuid = requestUuid)) {
            is Result.Success -> null

            is Result.Error -> {
                warn("refuseRequest: could not refuse the request: ${refusal.error}")

                // Not refused, accepted: its sender is a contact now, backed up as after an acceptance.
                if (refusal.error == AnswerContactRequestError.AlreadyAccepted) backUp(caller = "refuseRequest")

                refusal.error.toAnswerFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Blocks the sender of the contact request [requestUuid], refusing it first. Null once blocked. */
    suspend fun blockRequestSender(requestUuid: String): AnswerContactRequestFailure? =
        when (val blocking = contactRequestRepository.blockSender(requestUuid = requestUuid)) {
            is Result.Success -> {
                backUp(caller = "blockRequestSender")
                null
            }

            is Result.Error -> {
                warn("blockRequestSender: could not block the sender: ${blocking.error}")

                // Not refused, accepted, as for a refusal: its sender is a contact now.
                if (blocking.error == AnswerContactRequestError.AlreadyAccepted) backUp(caller = "blockRequestSender")

                blocking.error.toAnswerFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Why [username] cannot be looked up as typed, or null when it is valid, or empty. */
    fun validateUsername(username: String): UsernameInputFailure? =
        app.lidory.duckcount.validateUsername(username = username)?.toInputFailure()

    /** Sends a contact request to whoever reserved the discovery [username]. Null once it is sent. */
    suspend fun sendRequest(username: String): AddContactFailure? =
        when (val request = contactRequestRepository.sendContactRequest(username = username)) {
            is Result.Success -> null

            is Result.Error -> {
                warn("sendRequest: could not send the request: ${request.error}")
                request.error.toAddContactFailure(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Backs up the contacts the vault does not hold yet, and reads the missing names. */
    private suspend fun backUp(caller: String) {
        contactSync.backUp().onError { error -> warn("$caller: could not back the contacts up: $error") }
    }

    private fun warn(message: String) = println("$TAG: $message")
}
