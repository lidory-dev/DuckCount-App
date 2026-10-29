package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the group form lists once members are picked, see [keptMembers], and when it can be
 * confirmed, see [canConfirm], for a group to create or one being edited.
 */
class CreateGroupFormTest {

    private val self = member(userUuid = "self", fullName = "Me", isAdmin = true)
    private val alice = member(userUuid = "alice", fullName = "Alice")
    private val bob = member(userUuid = "bob", fullName = "Bob")
    private val carol = member(userUuid = "carol", fullName = "Carol")

    /** Picking nobody once left the user out of their own group, which could then not be created. */
    @Test
    fun `the user stays a member when nobody is picked`() {
        assertEquals(
            listOf(self),
            keptMembers(members = listOf(self, alice, bob), selfUuid = "self", selectedUuids = emptySet()),
        )
    }

    @Test
    fun `whoever is still picked stays in the order the form listed them`() {
        assertEquals(
            listOf(self, alice, carol),
            keptMembers(
                members = listOf(self, alice, bob, carol),
                selfUuid = "self",
                selectedUuids = linkedSetOf("carol", "alice"),
            ),
        )
    }

    @Test
    fun `whoever is picked and not listed yet is left to be added`() {
        assertEquals(
            listOf(self, alice),
            keptMembers(members = listOf(self, alice), selfUuid = "self", selectedUuids = setOf("alice", "bob")),
        )
    }

    @Test
    fun `the user alone cannot confirm a group`() {
        assertFalse(
            form(members = listOf(self))
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `the user and somebody else with a title can confirm a group`() {
        assertTrue(
            form(members = listOf(self, alice))
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    /** Its members are changed from its settings, so the form lists none of them. */
    @Test
    fun `a group being edited can be confirmed with no members listed`() {
        assertTrue(
            form(members = emptyList(), shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a group being edited with no title cannot be confirmed`() {
        assertFalse(
            form(members = emptyList(), title = "", shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    /** It would wake every other member for nothing, and push the oldest version out of the history. */
    @Test
    fun `a group being edited to what it shows cannot be confirmed`() {
        assertFalse(
            form(members = emptyList(), title = SHOWN.title, description = SHOWN.description, shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a group being edited with its title alone changed can be confirmed`() {
        assertTrue(
            form(members = emptyList(), title = "Ski trip 2027", description = SHOWN.description, shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a group being edited with its description alone changed can be confirmed`() {
        assertTrue(
            form(members = emptyList(), title = SHOWN.title, description = "", shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    /** Saved without it, it would be the same title and description again. */
    @Test
    fun `a group being edited with nothing but whitespace added around it cannot be confirmed`() {
        assertFalse(
            form(
                members = emptyList(),
                title = " ${SHOWN.title} ",
                description = "${SHOWN.description}\n\n",
                shown = SHOWN,
            ).canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a title of nothing but spaces cannot be confirmed`() {
        assertFalse(
            form(members = listOf(self, alice), title = "   ")
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
        assertFalse(
            form(members = emptyList(), title = "   ", shown = SHOWN)
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    /** Counted as it is saved, without the whitespace around it. */
    @Test
    fun `a title past its limit by the whitespace around it alone can be confirmed`() {
        assertTrue(
            form(members = listOf(self, alice), title = " ${"a".repeat(50)} ")
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a description past its limit by the whitespace around it alone can be confirmed`() {
        assertTrue(
            form(members = listOf(self, alice), description = "${"a".repeat(2000)}\n")
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a group with no title cannot be confirmed`() {
        assertFalse(
            form(members = listOf(self, alice), title = "")
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a title past its limit cannot be confirmed`() {
        assertFalse(
            form(members = listOf(self, alice), title = "a".repeat(51))
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    @Test
    fun `a description past its limit cannot be confirmed`() {
        assertFalse(
            form(members = listOf(self, alice), description = "a".repeat(2001))
                .canConfirm(maxTitleLength = 50, maxDescriptionLength = 2000),
        )
    }

    private fun member(userUuid: String, fullName: String?, isAdmin: Boolean = false) = GroupMemberWithFullName(
        member = GroupMember(userUuid = userUuid, groupUuid = "", isAdmin = isAdmin),
        fullName = fullName,
    )

    private fun form(
        members: List<GroupMemberWithFullName>,
        title: String = "Trip",
        description: String = "",
        shown: GroupInformation? = null,
    ) = CreateGroupState.Content(
        title = title,
        description = description,
        currency = Currency(
            uuid = "f4c08b14-5847-4d50-87b0-6ca4f3e961e1",
            name = "EUR",
            symbol = "€",
            hasSubunits = true,
        ),
        members = members,
        authenticatedUserUuid = "self",
        shown = shown,
    )
}

/** What the group being edited shows, see [CreateGroupState.Content.shown]. */
private val SHOWN = GroupInformation(title = "Ski trip", description = "Chalet and lift passes")
