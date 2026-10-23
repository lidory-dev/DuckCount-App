package networking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val USER = "22222222-2222-2222-2222-222222222222"

/**
 * Which requests a debug build logs, see [isLoggable]: none whose bodies would let whoever reads
 * the log act as the user.
 */
class LoggableRequestTest {

    /** Their bodies carry the long-lived token one way or the other. */
    @Test
    fun `the authentication routes are not logged`() {
        listOf("/auth/registrations", "/auth/sessions", "/auth/token").forEach { path ->
            assertFalse(isLoggable(encodedPath = path), path)
        }
    }

    /** An OPAQUE login on the user's password, as signing in runs. */
    @Test
    fun `the account deletion is not logged`() {
        assertFalse(isLoggable(encodedPath = "/users/$USER/deletion"))
    }

    /** OPAQUE messages of both passwords, and a new pair of tokens in the answer to the second. */
    @Test
    fun `the password change is not logged`() {
        assertFalse(isLoggable(encodedPath = "/users/$USER/password"))
    }

    @Test
    fun `the other routes are logged`() {
        listOf("/users/$USER/username", "/users/$USER/profile", "/vault", "/messages").forEach { path ->
            assertTrue(isLoggable(encodedPath = path), path)
        }
    }
}
