package group

import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a `DELETE /groups/{group_uuid}/membership` is read as, see [leaveAnswerOf]. */
class GroupLeaveAnswerTest {

    /** Whether this request took the user out or an earlier one did: leaving twice changes nothing. */
    @Test
    fun `a 200 is a group left`() {
        assertEquals(GroupLeaveAnswer.Left, leaveAnswerOf(sent = Result.Success(Unit)))
    }

    /** No such group, or the user was never in it: or the proxy answering while the server is redeployed. */
    @Test
    fun `a 404 is a group the user is no member of`() {
        assertEquals(GroupLeaveAnswer.NotMember, leaveAnswerOf(sent = Result.Error(NetworkError.NOT_FOUND)))
    }

    /**
     * The client reads a `403` as no answer it knows, so the owner's refusal is one more failure.
     * The sync never sends the owner's leave again: the next pass reads the user as the group's
     * listed owner and skips it, see [GroupSync].
     */
    @Test
    fun `the 403 refusing the owner is a failure like any unknown answer`() {
        assertEquals(
            GroupLeaveAnswer.Failed(error = NetworkError.UNKNOWN),
            leaveAnswerOf(sent = Result.Error(NetworkError.UNKNOWN)),
        )
    }

    /** A `401` a refresh may still turn around, and the leave may have been taken with its answer lost. */
    @Test
    fun `an answer that says nothing of the leave is sent again`() {
        listOf(
            NetworkError.UNAUTHORIZED,
            NetworkError.SERVER_ERROR,
            NetworkError.REQUEST_TIMEOUT,
            NetworkError.NO_INTERNET,
            NetworkError.UNREACHABLE,
            NetworkError.TOO_MANY_REQUESTS,
        ).forEach {
            assertEquals(GroupLeaveAnswer.Failed(error = it), leaveAnswerOf(sent = Result.Error(it)))
        }
    }
}
