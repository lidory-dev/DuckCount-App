package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.DeleteResult
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import database.domain.CurrencyCatalog
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import database.domain.GroupWithMembers as GroupWithMembersDb
import group.GroupDeleteError as SharedGroupDeleteError

/**
 * What the group's settings are told of whether the user may delete it, see [toGroupListModel], and
 * of what came of deleting it, see [toDeleteResult].
 */
class GroupDeleteMappingTest {

    @Test
    fun `a deletion the server took is told as deleted`() {
        assertEquals(DeleteResult.Deleted, Result.Success(Unit).toDeleteResult(isOnline = true))
    }

    /** It is made while the user is there to hear of it: what kept it from going through is theirs to act on. */
    @Test
    fun `no internet is told as such`() {
        assertEquals(
            DeleteResult.Failed(GroupCreationFailure.NO_INTERNET),
            Result.Error(SharedGroupDeleteError.Network(NetworkError.NO_INTERNET)).toDeleteResult(isOnline = false),
        )
    }

    @Test
    fun `every other failure is told as one the user cannot act on`() {
        listOf(
            SharedGroupDeleteError.NoSession,
            SharedGroupDeleteError.DeviceLocked,
            SharedGroupDeleteError.NotHeld,
            SharedGroupDeleteError.NotOwner,
            SharedGroupDeleteError.ChangePending,
            SharedGroupDeleteError.Storage,
        ).forEach { error ->
            assertEquals(DeleteResult.Failed(GroupCreationFailure.UNKNOWN), Result.Error(error).toDeleteResult(isOnline = true))
        }
    }

    @Test
    fun `whether the user may delete the group and why they are out of it reach the settings`() {
        assertTrue(groupOf(canDelete = true).toGroupListModel().canDelete)
        assertFalse(groupOf(canDelete = false).toGroupListModel().canDelete)
        assertTrue(groupOf(isDeleted = true).toGroupListModel().isDeleted)
        assertFalse(groupOf(isDeleted = false).toGroupListModel().isDeleted)
    }

    private fun groupOf(canDelete: Boolean = false, isDeleted: Boolean = false) = GroupWithMembersDb(
        uuid = "group",
        description = "",
        title = "Ski trip",
        currency = CurrencyCatalog.defaultCurrency,
        groupMembers = emptyList(),
        isLeft = isDeleted,
        isDeleted = isDeleted,
        hasPendingChange = false,
        canChangeMembers = false,
        canEdit = false,
        canVouchForMembers = false,
        canLeave = false,
        canDelete = canDelete,
    )
}
