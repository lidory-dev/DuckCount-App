package authenticationUtils

/**
 * Something the platform keeps of a session outside the vault and the database, which
 * `DuckCountClient.endSession` wipes along with them: whoever signs in next on this device
 * must not find any of it.
 *
 * On Android, the notifications in the shade, which name the user's contacts and what they
 * shared, and the app's own record of which of them are up.
 */
interface SessionLeftovers {

    /**
     * Wipes it, for good by the time this returns: a session usually ends with the process
     * being killed right after.
     */
    suspend fun clear()
}
