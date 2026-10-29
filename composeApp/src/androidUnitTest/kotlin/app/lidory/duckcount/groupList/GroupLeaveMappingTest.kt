package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.LeaveResult
import database.domain.CurrencyCatalog
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import database.domain.GroupWithMembers as GroupWithMembersDb
import group.GroupLeaveError as SharedGroupLeaveError

/**
 * What the group's settings are told of whether the user may leave it, see [toGroupListModel],
 * and of what came of leaving it, see [toLeaveResult].
 */
class GroupLeaveMappingTest {

    @Test
    fun `a leave recorded is told as left`() {
        assertEquals(LeaveResult.Left, Result.Success(Unit).toLeaveResult())
    }

    /** Never the network: once recorded, the leave is sent again until the server hears it. */
    @Test
    fun `every failure to leave is told as one the user cannot act on`() {
        listOf(
            SharedGroupLeaveError.NoSession,
            SharedGroupLeaveError.DeviceLocked,
            SharedGroupLeaveError.NotHeld,
            SharedGroupLeaveError.IsCreator,
            SharedGroupLeaveError.Storage,
        ).forEach { error ->
            assertEquals(LeaveResult.Failed(GroupCreationFailure.UNKNOWN), Result.Error(error).toLeaveResult(), "$error")
        }
    }

    @Test
    fun `whether the user may leave the group reaches the settings`() {
        assertTrue(groupOf(canLeave = true).toGroupListModel().canLeave)
        assertFalse(groupOf(canLeave = false).toGroupListModel().canLeave)
    }

    private fun groupOf(canLeave: Boolean) = GroupWithMembersDb(
        uuid = "group",
        description = "",
        title = "Ski trip",
        currency = CurrencyCatalog.defaultCurrency,
        groupMembers = emptyList(),
        isLeft = false,
        hasPendingChange = false,
        canChangeMembers = false,
        canEdit = false,
        canVouchForMembers = true,
        canLeave = canLeave,
    )
}
