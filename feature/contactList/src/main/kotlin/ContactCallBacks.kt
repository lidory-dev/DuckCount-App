import kotlinx.coroutines.flow.Flow
import model.AddContactError
import model.AnswerContactRequestError
import model.BlockedUser
import model.ContactRequest
import model.ContactVerification
import model.RemoveContactError
import model.User
import model.UsernameInputError

interface ContactCallBacks {

    /** Longest username `lib-duckcount` accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int

    suspend fun getFilteredUsers(filter: String): Flow<List<User>>

    suspend fun getUser(uuid: String): Flow<User>

    fun getAuthenticatedUserUuid(): String?

    /**
     * Removes the contact [userUuid], here and from the vault, and tells them so that they
     * remove the user in turn.
     *
     * Returns null once removed, or why it could not be. Nothing is removed until they have
     * been told, so the user can try again.
     */
    suspend fun removeContact(userUuid: String): RemoveContactError?

    /**
     * Blocks the contact [userUuid]: removes them as [removeContact] does, telling them as for any
     * removal, never that they were blocked, then refuses anything coming from them from then on,
     * their contact requests included.
     *
     * Returns null once blocked, or why it could not be. Nothing is done until they have been told,
     * so the user can try again.
     */
    suspend fun blockContact(userUuid: String): RemoveContactError?

    /** Emits everybody the user blocked, again whenever that changes. */
    fun getBlockedUsers(): Flow<List<BlockedUser>>

    /**
     * Lifts the block on [userUuid]. They are no contact of the user's for it: a new request, from
     * either of the two, is what makes them one again.
     */
    suspend fun unblock(userUuid: String)

    /**
     * Emits where the user stands with checking that the contact [userUuid] is who this device
     * talks to, again whenever that changes: the safety number the two of them compare, and
     * whether the user marked it verified.
     */
    fun getVerification(userUuid: String): Flow<ContactVerification>

    /**
     * Marks [safetyNumber], the one the user compared with the contact [userUuid], verified, here
     * and in the vault. Only that number counts as verified, should the one computed for them
     * ever differ.
     *
     * Marked on this device first, which is what the user sees: a vault write failing is retried
     * by the next refresh rather than reported.
     */
    suspend fun markVerified(userUuid: String, safetyNumber: String)

    /** Clears the mark [markVerified] made for the contact [userUuid], the same way. */
    suspend fun clearVerification(userUuid: String)

    /**
     * Emits the username the user reserved to be discoverable by, or null when they
     * have not reserved one yet.
     *
     * Reserving one is the settings' business, this is only used to tell the user the
     * contact list is waiting on it.
     */
    fun getReservedUsername(): Flow<String?>

    /** How many contact requests are waiting for the user, straight off the local cache. */
    fun getContactRequestCount(): Flow<Int>

    /** The contact requests waiting for the user, oldest first, straight off the local cache. */
    fun getContactRequests(): Flow<List<ContactRequest>>

    /**
     * Brings the contacts and the contact requests up to date: pulls in the requests
     * addressed to the user, takes in what contacts sent them (the answers to the requests
     * they sent, and the notices of contacts who removed them), and carries to the vault
     * the changes it has not taken yet.
     *
     * Failing is not reported: the user keeps seeing what they already had, which is what
     * caching it is for, and the next refresh picks up whatever did not get done.
     */
    suspend fun refreshContacts()

    /**
     * Called when the contact list is in front of the user.
     */
    fun contactsSeen()

    /**
     * Called when the contact requests are in front of the user.
     */
    fun contactRequestsSeen()

    /**
     * Accepts the contact request [requestUuid], making its sender a contact.
     *
     * Returns null once accepted, or why it could not be.
     */
    suspend fun acceptContactRequest(requestUuid: String): AnswerContactRequestError?

    /**
     * Refuses the contact request [requestUuid]. Its sender is not told.
     *
     * Returns null once refused, or why it could not be. A request the user accepted
     * earlier, without hearing back, may turn out to have been taken: its sender is then
     * added as a contact, which [AnswerContactRequestError.ALREADY_ACCEPTED] says.
     */
    suspend fun refuseContactRequest(requestUuid: String): AnswerContactRequestError?

    /**
     * Blocks the sender of the contact request [requestUuid] without accepting it: refuses it as
     * [refuseContactRequest] does, which they are not told, then refuses anything coming from them
     * from then on, every request of theirs included.
     *
     * Returns null once blocked, or why it could not be, the same way as [refuseContactRequest]:
     * nobody is blocked while the request could not be refused, nor when it turns out accepted.
     */
    suspend fun blockContactRequestSender(requestUuid: String): AnswerContactRequestError?

    /**
     * Returns why [username] cannot be looked up as typed, or null when it is valid, or empty:
     * nothing typed yet isn't a mistake worth pointing out, the button stays disabled anyway.
     */
    fun validateUsername(username: String): UsernameInputError?

    /**
     * Sends a contact request to whoever reserved the discovery [username].
     *
     * Returns null once the request is sent, or why it could not be.
     */
    suspend fun sendContactRequest(username: String): AddContactError?
}
