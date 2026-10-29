package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupEditError
import database.domain.CurrencyCatalog
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import database.domain.GroupWithMembers as GroupWithMembersDb
import group.GroupEditError as SharedGroupEditError

/**
 * What the group's settings are told of whether the user may edit it, see [toGroupListModel], and
 * what the form is told of an edit that was not saved, see [toGroupEditError].
 */
class GroupEditMappingTest {

    @Test
    fun `whether the user may edit the group reaches the settings`() {
        assertTrue(groupOf(canEdit = true).toGroupListModel().canEdit)
        assertFalse(groupOf(canEdit = false).toGroupListModel().canEdit)
    }

    @Test
    fun `a change on its way to the server is told as such`() {
        assertEquals(GroupEditError.ChangePending, SharedGroupEditError.ChangePending.toGroupEditError(isOnline = true))
    }

    @Test
    fun `an edit the server found outdated is told as such`() {
        assertEquals(GroupEditError.Outdated, SharedGroupEditError.Outdated.toGroupEditError(isOnline = true))
    }

    @Test
    fun `no connectivity is no internet`() {
        assertEquals(
            GroupEditError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupEditError.Network(NetworkError.NO_INTERNET).toGroupEditError(isOnline = true),
        )
    }

    @Test
    fun `an unreachable server while online is a server unreachable`() {
        assertEquals(
            GroupEditError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupEditError.Network(NetworkError.UNREACHABLE).toGroupEditError(isOnline = true),
        )
    }

    @Test
    fun `an unreachable server while offline is no internet`() {
        assertEquals(
            GroupEditError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupEditError.Network(NetworkError.UNREACHABLE).toGroupEditError(isOnline = false),
        )
    }

    @Test
    fun `a server too slow to answer is a timeout`() {
        assertEquals(
            GroupEditError.Failure(GroupCreationFailure.TIMEOUT),
            SharedGroupEditError.Network(NetworkError.REQUEST_TIMEOUT).toGroupEditError(isOnline = true),
        )
    }

    /** The key ring the identity is read from not answering, as for a creation. */
    @Test
    fun `the vault unreachable as the identity opens is reported as a network failure`() {
        assertEquals(
            GroupEditError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupEditError.Identity(MlsIdentityError.Vault(VaultError.Network(NetworkError.UNREACHABLE)))
                .toGroupEditError(isOnline = true),
        )
    }

    /** Nothing the user can act on: what the settings offer no edit of among it. */
    @Test
    fun `every other failure is told as one the user cannot act on`() {
        listOf(
            SharedGroupEditError.NoSession,
            SharedGroupEditError.DeviceLocked,
            SharedGroupEditError.NotHeld,
            SharedGroupEditError.NotCreator,
            SharedGroupEditError.Crypto,
            SharedGroupEditError.Identity(MlsIdentityError.Crypto),
            SharedGroupEditError.Network(NetworkError.TOO_MANY_REQUESTS),
            SharedGroupEditError.Network(NetworkError.NOT_FOUND),
        ).forEach { error ->
            assertEquals(
                GroupEditError.Failure(GroupCreationFailure.UNKNOWN),
                error.toGroupEditError(isOnline = true),
                "$error",
            )
        }
    }

    private fun groupOf(canEdit: Boolean) = GroupWithMembersDb(
        uuid = "group",
        description = "",
        title = "Ski trip",
        currency = CurrencyCatalog.defaultCurrency,
        groupMembers = emptyList(),
        isLeft = false,
        hasPendingChange = false,
        canChangeMembers = false,
        canEdit = canEdit,
        canVouchForMembers = true,
        canLeave = false,
    )
}
