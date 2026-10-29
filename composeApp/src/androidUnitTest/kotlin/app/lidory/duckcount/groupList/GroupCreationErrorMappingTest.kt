package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.MemberRefusal
import contact.ContactSyncError
import kotlinx.coroutines.runBlocking
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import group.GroupCreationError as SharedGroupCreationError

/** What the group form is told of a creation that failed, see [toGroupCreationError]. */
class GroupCreationErrorMappingTest {

    private val names = mapOf("alice" to "Alice", "bob" to "Bob", "carol" to "Carol")

    private fun SharedGroupCreationError.mapped(isOnline: Boolean = true): GroupCreationError = runBlocking {
        toGroupCreationError(
            isOnline = isOnline,
            memberUuids = listOf("alice", "bob", "carol"),
            fullNameOf = { names[it] },
        )
    }

    /** The shared error names them in no particular order. */
    @Test
    fun `members are named in the order the form lists them`() {
        assertEquals(
            GroupCreationError.Members(refusal = MemberRefusal.UNREACHABLE, fullNames = listOf("Alice", "Carol")),
            SharedGroupCreationError.Unreachable(userUuids = linkedSetOf("carol", "alice")).mapped(),
        )
    }

    @Test
    fun `members who deleted their account are named for it`() {
        assertEquals(
            GroupCreationError.Members(refusal = MemberRefusal.DELETED, fullNames = listOf("Bob")),
            SharedGroupCreationError.Deleted(userUuids = setOf("bob")).mapped(),
        )
    }

    @Test
    fun `an unreachable server while offline is no internet`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupCreationError.Network(NetworkError.UNREACHABLE).mapped(isOnline = false),
        )
    }

    @Test
    fun `an unreachable server while online is told as such`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupCreationError.Network(NetworkError.UNREACHABLE).mapped(isOnline = true),
        )
    }

    /** Restoring the contacts reads the vault, where being offline shows first. */
    @Test
    fun `the vault timing out while restoring the contacts is a timeout`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.TIMEOUT),
            SharedGroupCreationError.Contacts(
                ContactSyncError.Vault(VaultError.Network(NetworkError.REQUEST_TIMEOUT)),
            ).mapped(),
        )
    }

    /** Sealing the creator's copy of the group's key reads the key ring, see `group.GroupKeyCopies`. */
    @Test
    fun `the key ring unreachable while offline is no internet`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupCreationError.Vault(VaultError.Network(NetworkError.UNREACHABLE)).mapped(isOnline = false),
        )
    }

    @Test
    fun `a key ring that does not open is unknown`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupCreationError.Vault(VaultError.MalformedKeyRing).mapped(),
        )
    }

    @Test
    fun `the server refusing the group is unknown`() {
        assertEquals(
            GroupCreationError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupCreationError.Network(NetworkError.BAD_REQUEST).mapped(),
        )
    }
}
