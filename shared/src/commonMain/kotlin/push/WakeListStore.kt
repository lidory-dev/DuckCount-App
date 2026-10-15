package push

/**
 * Where [WakeList] keeps who may wake the user, see [VaultWakeListStore].
 *
 * Somewhere a locked device can read, unlike the database and the rest of the vault, which is
 * the whole point: a push mostly arrives with the phone in a pocket, and who sent it has to be
 * checked then, see [PushSignal.mayWake]. Bound on Android only, iOS having no push yet.
 */
interface WakeListStore {

    /** Whether [userUuid] is one of the user's contacts, blocked ones aside. */
    fun isContact(userUuid: String): Boolean

    /**
     * Whether the user sent [userUuid] a contact request in the last week still waiting for its
     * answer.
     */
    fun isRequested(userUuid: String): Boolean

    /**
     * Replaces the whole list with [contacts] and the recipients of the [requested] contact
     * requests, written before this returns.
     */
    suspend fun replace(contacts: Set<String>, requested: Set<String>)

    /** Empties it, for good by the time this returns, as the session ends. */
    suspend fun clear()
}
