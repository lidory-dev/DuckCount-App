package database.data.repository

import contact.RemoveContactError
import database.data.remote.MessageLimitScope
import database.data.remote.SendDirectMessageError
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a removal makes of the server's answer to its notice, see [toRemovalNotice]: whether the
 * contact is forgotten, and whether the key to the user's profile then rotates away from them.
 */
class RemovalNoticeTest {

    @Test
    fun `a notice that went out removes the contact with a rotation`() {
        val notice = Result.Success(Unit).toRemovalNotice()

        assertEquals(Result.Success(RemovalNotice.DONE), notice)
        assertTrue(RemovalNotice.DONE.rotatesProfileKey)
    }

    /** The `410`: forgotten as if their own notice of the deletion had come in, owing no rotation. */
    @Test
    fun `a contact who deleted their account is removed without a rotation`() {
        val notice = Result.Error(SendDirectMessageError.RecipientDeleted).toRemovalNotice()

        assertEquals(Result.Success(RemovalNotice.RECIPIENT_DELETED), notice)
        assertFalse(RemovalNotice.RECIPIENT_DELETED.rotatesProfileKey)
    }

    /**
     * An account an administrator removed answers a `404` for good, and the proxy in front of the
     * API one for a while: neither says the account was deleted, so the removal goes ahead as for
     * a contact with no group left to tell them in, the key rotating away from them.
     */
    @Test
    fun `a recipient the server answers 404 for is removed with a rotation`() {
        val notice = Result.Error(SendDirectMessageError.Network(NetworkError.NOT_FOUND)).toRemovalNotice()

        assertEquals(Result.Success(RemovalNotice.DONE), notice)
    }

    @Test
    fun `a notice refused for a limit is left for the user to try again`() {
        val refused = SendDirectMessageError.RateLimited(retryAfterSeconds = 30, scope = MessageLimitScope.SENDER)

        assertEquals(
            Result.Error(RemoveContactError.RateLimited(scope = MessageLimitScope.SENDER)),
            Result.Error(refused).toRemovalNotice(),
        )
    }

    @Test
    fun `a notice the server could not take is left for the user to try again`() {
        assertEquals(
            Result.Error(RemoveContactError.Network(NetworkError.SERVER_ERROR)),
            Result.Error(SendDirectMessageError.Network(NetworkError.SERVER_ERROR)).toRemovalNotice(),
        )
    }
}
