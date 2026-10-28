package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whom the settings of a group offer to leave it, see [isLeavable]: a member still in it who did
 * not create it, the group as the shared layer maps it for each of them, see
 * [GroupWithMembers.canLeave].
 */
class LeaveActionTest {

    /** Changing its members is theirs alone, and nobody else could. */
    @Test
    fun `the creator is offered the members and not the leave`() {
        val group = group(canChangeMembers = true, canLeave = false)

        assertFalse(group.isLeavable())
        assertEquals(MemberActions.AVAILABLE, group.memberActions())
    }

    @Test
    fun `a member who did not create the group is offered the leave and not the members`() {
        val group = group(canChangeMembers = false, canLeave = true)

        assertTrue(group.isLeavable())
        assertEquals(MemberActions.NONE, group.memberActions())
    }

    /** It stays read-only, with nothing left to do about it. */
    @Test
    fun `a group the user is no longer in offers neither`() {
        val group = group(canChangeMembers = false, canLeave = false, isLeft = true)

        assertFalse(group.isLeavable())
        assertEquals(MemberActions.NONE, group.memberActions())
    }

    private fun group(
        canChangeMembers: Boolean,
        canLeave: Boolean,
        isLeft: Boolean = false,
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
        isLeft = isLeft,
        hasPendingChange = false,
        canChangeMembers = canChangeMembers,
        canVouchForMembers = !isLeft,
        canLeave = canLeave,
    )
}
