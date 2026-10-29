package app.lidory.duckcount.groupList.model

import app.lidory.duckcount.groupList.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the history screen says of a history that could not be read, see
 * [GroupHistoryError.messageRes]: what kept it from being read in a creation's words, and the
 * device locking, and a history it cannot read yet, in words of their own.
 */
class GroupHistoryMessageTest {

    @Test
    fun `the device locking is told as such`() {
        assertEquals(R.string.group_history_error_device_locked, GroupHistoryError.DeviceLocked.messageRes())
    }

    @Test
    fun `a history this device cannot read yet is told as such`() {
        assertEquals(R.string.group_history_error_unreadable, GroupHistoryError.Unreadable.messageRes())
    }

    @Test
    fun `what kept it from being read is told as for a creation`() {
        GroupCreationFailure.entries.forEach { failure ->
            assertEquals(failure.messageRes(), GroupHistoryError.Failure(failure).messageRes())
        }
    }
}
