package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may add and remove members of a group from its settings, see [memberActions], and whom,
 * see [isRemovable]: its creator alone, from a device that holds the group.
 */
class MemberActionsTest {

    @Test
    fun `the creator may change the members`() {
        assertEquals(MemberActions.AVAILABLE, group(canChangeMembers = true).memberActions())
    }

    @Test
    fun `a member who did not create the group may not change its members`() {
        assertEquals(MemberActions.NONE, group(canChangeMembers = false).memberActions())
    }

    @Test
    fun `the creator waits for a change on its way to the server`() {
        assertEquals(MemberActions.WAITING, group(canChangeMembers = true, hasPendingChange = true).memberActions())
    }

    /** A creation on its way counts as a change, see [GroupWithMembers.hasPendingChange]. */
    @Test
    fun `a member who did not create the group is offered nothing whatever is pending`() {
        assertEquals(MemberActions.NONE, group(canChangeMembers = false, hasPendingChange = true).memberActions())
    }

    @Test
    fun `the creator may remove another member`() {
        assertTrue(isRemovable(userUuid = "bob", selfUuid = "alice", actions = MemberActions.AVAILABLE))
    }

    @Test
    fun `the creator may not remove themself`() {
        assertFalse(isRemovable(userUuid = "alice", selfUuid = "alice", actions = MemberActions.AVAILABLE))
    }

    @Test
    fun `nobody is removed while a change is on its way`() {
        assertFalse(isRemovable(userUuid = "bob", selfUuid = "alice", actions = MemberActions.WAITING))
    }

    @Test
    fun `a member who did not create the group may remove nobody`() {
        assertFalse(isRemovable(userUuid = "bob", selfUuid = "carol", actions = MemberActions.NONE))
    }

    private fun group(
        canChangeMembers: Boolean,
        hasPendingChange: Boolean = false,
    ) = GroupWithMembers(
        uuid = "group",
        description = "",
        title = "Trip",
        currency = Currency(
            uuid = "f4c08b14-5847-4d50-87b0-6ca4f3e961e1",
            name = "EUR",
            symbol = "€",
            hasSubunits = true,
        ),
        members = listOf(
            GroupMember(userUuid = "alice", groupUuid = "group", isAdmin = true),
            GroupMember(userUuid = "bob", groupUuid = "group", isAdmin = false),
        ),
        isLeft = false,
        hasPendingChange = hasPendingChange,
        canChangeMembers = canChangeMembers,
        canVouchForMembers = true,
        canLeave = !canChangeMembers,
    )
}
