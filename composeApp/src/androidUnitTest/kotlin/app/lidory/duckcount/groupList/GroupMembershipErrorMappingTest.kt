package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.MemberRefusal
import app.lidory.duckcount.groupList.model.MembershipChangeError
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import contact.ContactSyncError
import kotlinx.coroutines.runBlocking
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import group.GroupMembershipError as SharedGroupMembershipError
import group.MembershipChangeOutcome as SharedMembershipChangeOutcome

/**
 * What the group's settings are told of a change to its members, see [toMembershipChangeResult]
 * and [toMembershipChangeError].
 */
class GroupMembershipErrorMappingTest {

    private val names = mapOf("alice" to "Alice", "bob" to "Bob", "carol" to "Carol")

    private fun SharedGroupMembershipError.mapped(isOnline: Boolean = true): MembershipChangeError = runBlocking {
        toMembershipChangeError(
            isOnline = isOnline,
            memberUuids = listOf("alice", "bob", "carol"),
            fullNameOf = { names[it] },
        )
    }

    @Test
    fun `a change the server took is made`() {
        assertEquals(MembershipChangeResult.Made, SharedMembershipChangeOutcome.MADE.toMembershipChangeResult())
    }

    @Test
    fun `a change the server has yet to take is pending`() {
        assertEquals(MembershipChangeResult.Pending, SharedMembershipChangeOutcome.PENDING.toMembershipChangeResult())
    }

    /** The shared error names them in no particular order. */
    @Test
    fun `members are named in the order they were picked`() {
        assertEquals(
            MembershipChangeError.Members(refusal = MemberRefusal.NOT_CONTACTS, fullNames = listOf("Alice", "Carol")),
            SharedGroupMembershipError.NotContacts(userUuids = linkedSetOf("carol", "alice")).mapped(),
        )
    }

    @Test
    fun `members whose safety number changed are named for it`() {
        assertEquals(
            MembershipChangeError.Members(refusal = MemberRefusal.IDENTITY_CHANGED, fullNames = listOf("Bob")),
            SharedGroupMembershipError.IdentityChanged(userUuids = setOf("bob")).mapped(),
        )
    }

    @Test
    fun `members with nothing to be added with are named for it`() {
        assertEquals(
            MembershipChangeError.Members(refusal = MemberRefusal.UNREACHABLE, fullNames = listOf("Alice", "Bob")),
            SharedGroupMembershipError.Unreachable(userUuids = setOf("bob", "alice")).mapped(),
        )
    }

    @Test
    fun `members who deleted their account are named for it`() {
        assertEquals(
            MembershipChangeError.Members(refusal = MemberRefusal.DELETED, fullNames = listOf("Carol")),
            SharedGroupMembershipError.Deleted(userUuids = setOf("carol")).mapped(),
        )
    }

    @Test
    fun `an unreachable server while offline is no internet`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.NO_INTERNET),
            SharedGroupMembershipError.Network(NetworkError.UNREACHABLE).mapped(isOnline = false),
        )
    }

    @Test
    fun `an unreachable server while online is told as such`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.SERVER_UNREACHABLE),
            SharedGroupMembershipError.Network(NetworkError.UNREACHABLE).mapped(isOnline = true),
        )
    }

    /** Restoring the contacts reads the vault, where being offline shows first. */
    @Test
    fun `the vault timing out while restoring the contacts is a timeout`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.TIMEOUT),
            SharedGroupMembershipError.Contacts(
                ContactSyncError.Vault(VaultError.Network(NetworkError.REQUEST_TIMEOUT)),
            ).mapped(),
        )
    }

    /** Every Commit carries the user's copy of the next key, sealed under the key ring the vault holds. */
    @Test
    fun `the vault timing out while sealing the key copy is a timeout`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.TIMEOUT),
            SharedGroupMembershipError.Vault(VaultError.Network(NetworkError.REQUEST_TIMEOUT)).mapped(),
        )
    }

    @Test
    fun `the vault failing otherwise while sealing the key copy is unknown`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupMembershipError.Vault(VaultError.Crypto).mapped(),
        )
    }

    /** Another member's Commit came first, twice: asking again is all there is to do. */
    @Test
    fun `a change built in an epoch the group left is unknown`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupMembershipError.Outdated.mapped(),
        )
    }

    @Test
    fun `the server refusing the change is unknown`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupMembershipError.Network(NetworkError.BAD_REQUEST).mapped(),
        )
    }

    /** Nothing was changed, and nothing the user can act on but asking again once it is unlocked. */
    @Test
    fun `the device locking as the change waited is unknown`() {
        assertEquals(
            MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN),
            SharedGroupMembershipError.DeviceLocked.mapped(),
        )
    }

    /** The settings offer no change while one is pending, nor past the limit. */
    @Test
    fun `what the settings never offer is unknown`() {
        listOf(
            SharedGroupMembershipError.ChangePending,
            SharedGroupMembershipError.TooManyMembers,
            SharedGroupMembershipError.NotCreator,
            SharedGroupMembershipError.NotHeld,
        ).forEach { error ->
            assertEquals(MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN), error.mapped())
        }
    }
}
