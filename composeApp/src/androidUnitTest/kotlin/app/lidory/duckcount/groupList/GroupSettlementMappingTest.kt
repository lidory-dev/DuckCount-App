package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationFailure
import group.GroupExpenseWriteError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the settle-up screen tells of a settlement that was not recorded, see [toGroupCreationFailure]. */
class GroupSettlementMappingTest {

    @Test
    fun `the server unreachable online is the server unreachable`() {
        assertEquals(
            GroupCreationFailure.SERVER_UNREACHABLE,
            GroupExpenseWriteError.Network(NetworkError.UNREACHABLE).toGroupCreationFailure(isOnline = true),
        )
    }

    @Test
    fun `the server unreachable offline is no internet`() {
        assertEquals(
            GroupCreationFailure.NO_INTERNET,
            GroupExpenseWriteError.Network(NetworkError.UNREACHABLE).toGroupCreationFailure(isOnline = false),
        )
    }

    /** The identity signing it opens first, reading the vault from the server. */
    @Test
    fun `the vault timing out while opening the identity is a timeout`() {
        val error = GroupExpenseWriteError.Identity(MlsIdentityError.Vault(VaultError.Network(NetworkError.REQUEST_TIMEOUT)))

        assertEquals(GroupCreationFailure.TIMEOUT, error.toGroupCreationFailure(isOnline = true))
    }

    /** Removed, the server saying so before this device's sync read it. */
    @Test
    fun `a group the user is no longer in is told as such`() {
        assertEquals(GroupCreationFailure.GROUP_LEFT, GroupExpenseWriteError.NotMember.toGroupCreationFailure(isOnline = true))
    }

    /** Writes in the user's groups past the server's limit: tried again at once, it refuses again. */
    @Test
    fun `the server's limit is told as such`() {
        assertEquals(
            GroupCreationFailure.RATE_LIMITED,
            GroupExpenseWriteError.RateLimited(retryAfterSeconds = 60).toGroupCreationFailure(isOnline = true),
        )
    }

    @Test
    fun `anything the user cannot act on is unknown`() {
        listOf(
            GroupExpenseWriteError.KeyNotCurrent,
            GroupExpenseWriteError.Conflict,
        ).forEach { error ->
            assertEquals(GroupCreationFailure.UNKNOWN, error.toGroupCreationFailure(isOnline = true), "$error")
        }
    }
}
