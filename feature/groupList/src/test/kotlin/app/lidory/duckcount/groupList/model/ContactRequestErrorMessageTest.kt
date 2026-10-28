package app.lidory.duckcount.groupList.model

import app.lidory.duckcount.groupList.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the sheet of a member says of a contact request to them that was not sent, see [ContactRequestError.messageRes]. */
class ContactRequestErrorMessageTest {

    /** The group cannot vouch for them: nothing the user can do from that sheet about it. */
    @Test
    fun `a member the group cannot vouch for is told as such`() {
        assertEquals(
            R.string.member_contact_request_error_not_in_group,
            ContactRequestError.NOT_IN_GROUP.messageRes(),
        )
    }

    @Test
    fun `a changed safety number is told as such`() {
        assertEquals(
            R.string.member_contact_request_error_identity_changed,
            ContactRequestError.IDENTITY_CHANGED.messageRes(),
        )
    }

    @Test
    fun `every error is told in words of its own`() {
        assertEquals(
            ContactRequestError.entries.size,
            ContactRequestError.entries.map { it.messageRes() }.toSet().size,
        )
    }
}
