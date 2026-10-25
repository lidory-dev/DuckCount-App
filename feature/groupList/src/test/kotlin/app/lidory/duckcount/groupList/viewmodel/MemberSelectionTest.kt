package app.lidory.duckcount.groupList.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking a group's members one by one never goes past the limit, the user who creates it
 * counting against it, see [memberCount], [isSelectable] and [toggledSelection].
 */
class MemberSelectionTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"

    @Test
    fun `the user counts as a member with nobody picked`() {
        assertEquals(1, memberCount(selectedUuids = emptySet()))
    }

    @Test
    fun `the user counts on top of whoever is picked`() {
        assertEquals(3, memberCount(selectedUuids = setOf(alice, bob)))
    }

    @Test
    fun `picking someone below the limit adds them`() {
        assertEquals(setOf(alice, bob), toggledSelection(selected = setOf(alice), uuid = bob, maxMembers = 3))
    }

    /** Two picked and the user make three. */
    @Test
    fun `picking someone once the user fills the last place does nothing`() {
        assertEquals(setOf(alice, bob), toggledSelection(selected = setOf(alice, bob), uuid = carol, maxMembers = 3))
    }

    @Test
    fun `unpicking someone at the limit takes them out`() {
        assertEquals(setOf(alice), toggledSelection(selected = setOf(alice, bob), uuid = bob, maxMembers = 3))
    }

    @Test
    fun `a card not picked is enabled below the limit`() {
        assertTrue(isSelectable(uuid = carol, selectedUuids = setOf(alice), maxMembers = 3))
    }

    @Test
    fun `a card not picked is disabled at the limit`() {
        assertFalse(isSelectable(uuid = carol, selectedUuids = setOf(alice, bob), maxMembers = 3))
    }

    /** So that the selection can still shrink once full. */
    @Test
    fun `a card picked stays enabled at the limit`() {
        assertTrue(isSelectable(uuid = bob, selectedUuids = setOf(alice, bob), maxMembers = 3))
    }

    @Test
    fun `ninety nine others fill a group of a hundred`() {
        val ninetyEight = (1..98).mapTo(LinkedHashSet()) { "member-$it" }

        val ninetyNine = toggledSelection(selected = ninetyEight, uuid = "member-99", maxMembers = 100)

        assertEquals(99, ninetyNine.size)
        assertEquals(100, memberCount(selectedUuids = ninetyNine))
        assertEquals(ninetyNine, toggledSelection(selected = ninetyNine, uuid = "member-100", maxMembers = 100))
    }
}
