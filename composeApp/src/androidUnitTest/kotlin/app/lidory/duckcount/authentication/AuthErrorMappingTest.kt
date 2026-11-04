package app.lidory.duckcount.authentication

import AuthError
import util.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told of an authentication that failed, see [toAuthError]. */
class AuthErrorMappingTest {

    @Test
    fun `a 403 in a registration is a phone the server refused`() {
        assertEquals(AuthError.DEVICE_NOT_ALLOWED, NetworkError.FORBIDDEN.toRegistrationError(isOnline = true))
    }

    /** Logging in is not gated on the phone, so a `403` there has nothing to say about it. */
    @Test
    fun `a 403 anywhere else is an unknown error`() {
        assertEquals(AuthError.UNKNOWN, NetworkError.FORBIDDEN.toAuthError(isOnline = true))
    }

    /** The `503` of a server that cannot run the check for now is told as any other. */
    @Test
    fun `a server error in a registration is a server error`() {
        assertEquals(AuthError.SERVER_ERROR, NetworkError.SERVER_ERROR.toRegistrationError(isOnline = true))
    }

    /** A challenge or a session that ran out, which at login would read as a wrong password. */
    @Test
    fun `a 404 in a registration is an unknown error`() {
        assertEquals(AuthError.UNKNOWN, NetworkError.NOT_FOUND.toRegistrationError(isOnline = true))
    }

    @Test
    fun `a 409 in a registration is a username taken`() {
        assertEquals(AuthError.USERNAME_TAKEN, NetworkError.CONFLICT.toRegistrationError(isOnline = true))
    }

    /** The proxy's limit, which answers on the challenge's route. */
    @Test
    fun `a 429 in a registration is too many attempts`() {
        assertEquals(AuthError.TOO_MANY_ATTEMPTS, NetworkError.TOO_MANY_REQUESTS.toRegistrationError(isOnline = true))
    }

    @Test
    fun `every other answer in a registration reads as it does anywhere else`() {
        (NetworkError.entries - NetworkError.FORBIDDEN - NetworkError.NOT_FOUND).forEach { error ->
            listOf(true, false).forEach { isOnline ->
                assertEquals(
                    error.toAuthError(isOnline = isOnline),
                    error.toRegistrationError(isOnline = isOnline),
                    "$error while online: $isOnline",
                )
            }
        }
    }
}
