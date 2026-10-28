package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the balances of a group name whoever they are about, see [membersWithBalance]: its members
 * and its former members alike, as names are read.
 */
class BalanceNamesTest {

    private val alice = GroupMember(userUuid = "alice", groupUuid = "group", isAdmin = true)
    private val bob = GroupMember(userUuid = "bob", groupUuid = "group", isAdmin = false)

    @Test
    fun `a name read after the balances showed shows`() {
        val names = FakeNames("alice" to "Alice")

        val balances = membersWithBalance(
            groupUuid = "group",
            members = listOf(alice, bob),
            debts = mapOf("alice" to "10", "bob" to "-10"),
            nameOf = names::of,
        )

        collecting(balances) { emissions ->
            assertEquals(listOf("Alice", null), emissions.receive().map { it.member.fullName })

            names["bob"] = "Bob"

            assertEquals(listOf("Alice", "Bob"), emissions.receive().map { it.member.fullName })
        }
    }

    /** The creator removed them: the expenses naming them stay, and so does their name. */
    @Test
    fun `a former member is named and told apart`() {
        val names = FakeNames("alice" to "Alice", "carol" to "Carol")

        val balances = membersWithBalance(
            groupUuid = "group",
            members = listOf(alice),
            debts = mapOf("alice" to "10", "carol" to "-10"),
            nameOf = names::of,
        )

        collecting(balances) { emissions ->
            val shown = emissions.receive()

            assertEquals(listOf("alice", "carol"), shown.map { it.member.member.userUuid })
            assertEquals(listOf("Alice", "Carol"), shown.map { it.member.fullName })
            assertEquals(listOf("10", "-10"), shown.map { it.balance })
            assertEquals(listOf(false, true), shown.map { it.isFormerMember })
        }
    }
}
