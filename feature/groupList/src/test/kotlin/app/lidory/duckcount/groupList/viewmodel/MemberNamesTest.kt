package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the settings of a group name its members, see [groupSettingsState], and the open sheet the
 * member it is about, see [sheetMember] and [shownMember]: as names are read, a member the user is
 * no contact of being named by a group sync once the screen is open.
 */
class MemberNamesTest {

    private val group = GroupWithMembers(
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
        hasPendingChange = false,
        canChangeMembers = false,
        canVouchForMembers = true,
    )

    @Test
    fun `the members are listed under the names this device holds`() {
        val names = FakeNames("alice" to "Alice", "bob" to "Bob")

        collecting(groupSettingsState(group = group, nameOf = names::of)) { states ->
            val state = states.receive()

            assertEquals(group, state.groupWithMembers)
            assertEquals(listOf("Alice", "Bob"), state.membersWithFullName.map { it.fullName })
        }
    }

    @Test
    fun `a name read after the screen opened shows in the members list`() {
        val names = FakeNames("alice" to "Alice")

        collecting(groupSettingsState(group = group, nameOf = names::of)) { states ->
            assertEquals(listOf("Alice", null), states.receive().membersWithFullName.map { it.fullName })

            names["bob"] = "Bob"

            assertEquals(listOf("Alice", "Bob"), states.receive().membersWithFullName.map { it.fullName })
        }
    }

    @Test
    fun `the open sheet shows a name read after it opened`() {
        val names = FakeNames("alice" to "Alice")
        val tapped = GroupMemberWithFullName(member = group.members[1], fullName = null)
        val sheet = sheetMember(opened = tapped, states = groupSettingsState(group = group, nameOf = names::of))

        collecting(sheet) { shown ->
            assertEquals(group to tapped, shown.receive())

            names["bob"] = "Bob"

            assertEquals(group to tapped.copy(fullName = "Bob"), shown.receive())
        }
    }

    /** A sync read their removal with the sheet open. */
    @Test
    fun `the sheet of a member the group lists no more shows them as tapped`() {
        val tapped = GroupMemberWithFullName(member = group.members[1], fullName = "Bob")

        assertEquals(
            tapped,
            shownMember(
                opened = tapped,
                members = listOf(GroupMemberWithFullName(member = group.members[0], fullName = "Alice")),
            ),
        )
    }
}
