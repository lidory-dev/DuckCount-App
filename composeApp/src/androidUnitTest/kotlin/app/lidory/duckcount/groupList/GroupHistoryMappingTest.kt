package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupHistoryError
import app.lidory.duckcount.groupList.model.GroupInformationVersion
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import group.GroupHistoryError as SharedGroupHistoryError
import group.GroupInformationVersion as SharedGroupInformationVersion

/**
 * What the history screen is told of each version of a group's information, see
 * [toGroupListModel], and of a history that could not be read, see [toGroupHistoryError].
 */
class GroupHistoryMappingTest {

    @Test
    fun `a version reaches the screen as the history gave it`() {
        val version = SharedGroupInformationVersion(
            revision = 2,
            title = "Ski trip 2027",
            description = "Chalet and the train",
            isCreation = false,
            isCurrent = true,
            revisedAt = 1793214127004L,
        )

        assertEquals(
            GroupInformationVersion(
                revision = 2,
                title = "Ski trip 2027",
                description = "Chalet and the train",
                isCreation = false,
                isCurrent = true,
                revisedAt = 1793214127004L,
            ),
            version.toGroupListModel(),
        )
    }

    @Test
    fun `the version a group was created as reaches the screen as such`() {
        val version = SharedGroupInformationVersion(
            revision = 1,
            title = "Ski trip",
            description = "",
            isCreation = true,
            isCurrent = false,
            revisedAt = 1793095200000L,
        )

        assertTrue(version.toGroupListModel().isCreation)
    }

    /** The server holding back the version the group shows, which leaves none current. */
    @Test
    fun `a version the group is not at reaches the screen as such`() {
        val version = SharedGroupInformationVersion(
            revision = 2,
            title = "Ski trip 2027",
            description = "",
            isCreation = false,
            isCurrent = false,
            revisedAt = 1793214127004L,
        )

        assertFalse(version.toGroupListModel().isCurrent)
    }

    @Test
    fun `the device locking is told as such`() {
        assertEquals(
            GroupHistoryError.DeviceLocked,
            SharedGroupHistoryError.DeviceLocked.toGroupHistoryError(isOnline = true),
        )
    }

    @Test
    fun `a history this device cannot read yet is told as such`() {
        assertEquals(
            GroupHistoryError.Unreadable,
            SharedGroupHistoryError.Unreadable.toGroupHistoryError(isOnline = true),
        )
    }

    @Test
    fun `no connectivity is no internet`() {
        assertEquals(
            GroupHistoryError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupHistoryError.Network(NetworkError.NO_INTERNET).toGroupHistoryError(isOnline = true),
        )
    }

    @Test
    fun `an unreachable server while online is a server unreachable`() {
        assertEquals(
            GroupHistoryError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupHistoryError.Network(NetworkError.UNREACHABLE).toGroupHistoryError(isOnline = true),
        )
    }

    @Test
    fun `an unreachable server while offline is no internet`() {
        assertEquals(
            GroupHistoryError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupHistoryError.Network(NetworkError.UNREACHABLE).toGroupHistoryError(isOnline = false),
        )
    }

    @Test
    fun `a server too slow to answer is a timeout`() {
        assertEquals(
            GroupHistoryError.Failure(GroupCreationFailure.TIMEOUT),
            SharedGroupHistoryError.Network(NetworkError.REQUEST_TIMEOUT).toGroupHistoryError(isOnline = true),
        )
    }

    /** The key ring the identity is read from not answering, as for an edit. */
    @Test
    fun `the vault unreachable as the identity opens is reported as a network failure`() {
        assertEquals(
            GroupHistoryError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupHistoryError.Identity(MlsIdentityError.Vault(VaultError.Network(NetworkError.UNREACHABLE)))
                .toGroupHistoryError(isOnline = true),
        )
    }

    /** Nothing the user can act on: what the settings offer no history of among it. */
    @Test
    fun `every other failure is told as one the user cannot act on`() {
        listOf(
            SharedGroupHistoryError.NoSession,
            SharedGroupHistoryError.NotMember,
            SharedGroupHistoryError.Storage,
            SharedGroupHistoryError.Identity(MlsIdentityError.Crypto),
            SharedGroupHistoryError.Network(NetworkError.NOT_FOUND),
            SharedGroupHistoryError.Network(NetworkError.SERVER_ERROR),
        ).forEach { error ->
            assertEquals(
                GroupHistoryError.Failure(GroupCreationFailure.UNKNOWN),
                error.toGroupHistoryError(isOnline = true),
                "$error",
            )
        }
    }
}
