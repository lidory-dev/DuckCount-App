package push

/**
 * The platform's half of push: whatever holds this device's endpoint.
 *
 * *Getting* an endpoint is shaped by the platform and stays on it. Android asks a
 * distributor, a separate app the user chose, and needs an activity to do it, since
 * the system may have to ask which one; iOS asks the system for an APNs token and
 * needs nothing. Neither fits a method both could implement.
 *
 * *Giving it up* is the same everywhere, and is what the code that ends a session
 * needs: hence this.
 */
interface PushRegistrar {

    /**
     * Gives up the endpoint, with the server and with the platform both.
     *
     * Called before the session ends, while the tokens the server call needs still
     * exist.
     */
    suspend fun tearDown()
}
