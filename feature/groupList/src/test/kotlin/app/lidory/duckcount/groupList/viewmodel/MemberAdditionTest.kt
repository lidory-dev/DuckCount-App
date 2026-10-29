package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.NobodyToPick
import app.lidory.duckcount.groupList.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking members to add to a group that exists: whom the picker lists, see [invitableUsers], and
 * how far it lets the user go, the members the group has counting against the limit, see
 * [memberCount], [isSelectable] and [toggledSelection], and whoever it picked and no longer lists
 * counting for nothing, see [listedPicks] and [toggledListedSelection].
 */
class MemberAdditionTest {

    private val alice = User(uuid = "alice", username = "alice", fullName = "Alice")
    private val bob = User(uuid = "bob", username = "bob", fullName = "Bob")
    private val carol = User(uuid = "carol", username = "carol", fullName = "Carol")

    @Test
    fun `the picker lists the contacts not in the group yet`() {
        assertEquals(
            listOf(bob),
            invitableUsers(users = listOf(alice, bob, carol), memberUuids = setOf("self", "alice", "carol")),
        )
    }

    @Test
    fun `the picker keeps the order the contacts come in`() {
        assertEquals(listOf(carol, alice), invitableUsers(users = listOf(carol, alice), memberUuids = setOf("self")))
    }

    /** Rather than that a search found nobody, with nothing searched. */
    @Test
    fun `the picker tells a user whose contacts are all in the group so`() {
        assertEquals(
            NobodyToPick.ALL_MEMBERS,
            nobodyToPick(contacts = listOf(alice, bob), memberUuids = setOf("self", "alice", "bob")),
        )
    }

    @Test
    fun `the picker tells a user with no contact so`() {
        assertEquals(NobodyToPick.NO_CONTACTS, nobodyToPick(contacts = emptyList(), memberUuids = setOf("self")))
    }

    /** A group to create has no members yet, the user aside, who is never among their contacts. */
    @Test
    fun `the picker tells a user creating a group with no contact so`() {
        assertEquals(NobodyToPick.NO_CONTACTS, nobodyToPick(contacts = emptyList(), memberUuids = emptySet()))
    }

    /** An empty list then means a search that found nobody. */
    @Test
    fun `the picker gives no reason while a contact can be picked`() {
        assertNull(nobodyToPick(contacts = listOf(alice, bob), memberUuids = setOf("self", "alice")))
        assertNull(nobodyToPick(contacts = listOf(alice), memberUuids = emptySet()))
    }

    @Test
    fun `the counter counts the members of the group with nobody picked`() {
        assertEquals(3, memberCount(selectedUuids = emptySet(), currentMemberCount = 3))
    }

    @Test
    fun `the counter counts whoever is picked on top of the members`() {
        assertEquals(5, memberCount(selectedUuids = setOf("bob", "dave"), currentMemberCount = 3))
    }

    @Test
    fun `a card not picked is enabled while the group has room`() {
        assertTrue(isSelectable(uuid = "bob", selectedUuids = setOf("dave"), maxMembers = 100, currentMemberCount = 98))
    }

    /** Ninety eight members and two picked make a hundred. */
    @Test
    fun `a card not picked is disabled once the members and those picked fill the group`() {
        assertFalse(
            isSelectable(uuid = "bob", selectedUuids = setOf("dave", "erin"), maxMembers = 100, currentMemberCount = 98),
        )
    }

    @Test
    fun `a card picked stays enabled once the group is full`() {
        assertTrue(
            isSelectable(uuid = "dave", selectedUuids = setOf("dave", "erin"), maxMembers = 100, currentMemberCount = 98),
        )
    }

    @Test
    fun `nobody can be picked for a group already full`() {
        assertFalse(isSelectable(uuid = "bob", selectedUuids = emptySet(), maxMembers = 100, currentMemberCount = 100))
        assertEquals(
            emptySet<String>(),
            toggledSelection(selected = emptySet(), uuid = "bob", maxMembers = 100, currentMemberCount = 100),
        )
    }

    @Test
    fun `picking stops at the last place the members leave`() {
        val one = toggledSelection(selected = emptySet(), uuid = "bob", maxMembers = 100, currentMemberCount = 99)

        assertEquals(setOf("bob"), one)
        assertEquals(one, toggledSelection(selected = one, uuid = "dave", maxMembers = 100, currentMemberCount = 99))
    }

    @Test
    fun `unpicking someone in a full group takes them out`() {
        assertEquals(
            setOf("dave"),
            toggledSelection(selected = setOf("dave", "erin"), uuid = "erin", maxMembers = 100, currentMemberCount = 98),
        )
    }

    /** A contact a sync forgot while the picker was open, who could not be unpicked there. */
    @Test
    fun `someone picked the picker no longer lists is no longer picked`() {
        assertEquals(setOf("bob"), listedPicks(picked = setOf("bob", "dave"), listedUuids = setOf("bob", "carol")))
    }

    @Test
    fun `someone picked the picker no longer lists leaves their place to somebody else`() {
        val picked = toggledListedSelection(
            picked = setOf("dave"),
            listedUuids = setOf("bob"),
            uuid = "bob",
            maxMembers = 100,
            currentMemberCount = 99,
        )

        assertEquals(setOf("bob"), listedPicks(picked = picked, listedUuids = setOf("bob")))
    }

    /** As when the list is read again, and emits nobody first. */
    @Test
    fun `someone picked the picker does not list yet is picked again once listed`() {
        val picked = toggledListedSelection(
            picked = setOf("dave"),
            listedUuids = emptySet(),
            uuid = "bob",
            maxMembers = 100,
            currentMemberCount = 3,
        )

        assertEquals(setOf("bob", "dave"), listedPicks(picked = picked, listedUuids = setOf("bob", "dave")))
    }

    @Test
    fun `unpicking someone the picker lists leaves whoever it does not list picked`() {
        assertEquals(
            setOf("dave"),
            toggledListedSelection(
                picked = setOf("bob", "dave"),
                listedUuids = setOf("bob"),
                uuid = "bob",
                maxMembers = 100,
                currentMemberCount = 3,
            ),
        )
    }
}
