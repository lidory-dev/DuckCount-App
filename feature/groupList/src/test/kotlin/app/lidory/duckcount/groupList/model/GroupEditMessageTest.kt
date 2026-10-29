package app.lidory.duckcount.groupList.model

import app.lidory.duckcount.groupList.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the form says of an edit of a group that was not saved, see [GroupEditError.messageRes]:
 * what kept it from going through in a creation's words, and what is an edit's own in words of its
 * own.
 */
class GroupEditMessageTest {

    @Test
    fun `a change on its way to the server is told as such`() {
        assertEquals(R.string.group_edit_error_change_pending, GroupEditError.ChangePending.messageRes())
    }

    @Test
    fun `an edit made over a version the server replaced is told as such`() {
        assertEquals(R.string.group_edit_error_outdated, GroupEditError.Outdated.messageRes())
    }

    @Test
    fun `an edit to what the group shows already is told as such`() {
        assertEquals(R.string.group_edit_error_unchanged, GroupEditError.Unchanged.messageRes())
    }

    @Test
    fun `what kept it from going through is told as for a creation`() {
        GroupCreationFailure.entries.forEach { failure ->
            assertEquals(failure.messageRes(), GroupEditError.Failure(failure).messageRes())
        }
    }
}
