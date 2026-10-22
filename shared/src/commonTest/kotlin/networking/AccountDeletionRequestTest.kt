package networking

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val USER = "22222222-2222-2222-2222-222222222222"

/**
 * Which request is the one deleting the account, whose `401` a refresh it sets off takes for the
 * account still being there, whatever the record of a deletion in doubt says, see
 * `DuckCountClient.expireSession`.
 */
class AccountDeletionRequestTest {

    @Test
    fun `the put to the user's deletion is the deletion`() {
        assertTrue(isAccountDeletion(method = HttpMethod.Put, encodedPath = "/users/$USER/deletion"))
    }

    /**
     * It deletes nothing, and its `401` says nothing of a deletion sent before it that never had
     * its answer: one that went through took the token the refresh turns down.
     */
    @Test
    fun `the post starting the re-authentication is not the deletion`() {
        assertFalse(isAccountDeletion(method = HttpMethod.Post, encodedPath = "/users/$USER/deletion"))
    }

    @Test
    fun `a put to another route of the user is not the deletion`() {
        assertFalse(isAccountDeletion(method = HttpMethod.Put, encodedPath = "/users/$USER/username"))
    }
}
