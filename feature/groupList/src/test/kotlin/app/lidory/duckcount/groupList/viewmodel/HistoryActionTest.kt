package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whom the settings of a group offer its history, see [isHistoryReadable]: every member still in
 * it, whom alone the server gives it to.
 */
class HistoryActionTest {

    @Test
    fun `the creator is offered the history`() {
        assertTrue(group(isCreator = true).isHistoryReadable())
    }

    @Test
    fun `a member who did not create the group is offered the history`() {
        assertTrue(group(isCreator = false).isHistoryReadable())
    }

    /** It stays read-only, with what the user had of it. */
    @Test
    fun `a group the user is no longer in offers no history`() {
        assertFalse(group(isCreator = false, isLeft = true).isHistoryReadable())
    }

    private fun group(isCreator: Boolean, isLeft: Boolean = false) = GroupWithMembers(
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
        canChangeMembers = isCreator,
        canEdit = isCreator,
        canVouchForMembers = !isLeft,
        canLeave = !isCreator && !isLeft,
    )
}
