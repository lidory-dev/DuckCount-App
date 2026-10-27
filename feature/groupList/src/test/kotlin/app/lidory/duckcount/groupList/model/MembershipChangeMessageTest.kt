package app.lidory.duckcount.groupList.model

import app.lidory.duckcount.groupList.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the group's settings say of members who could not be added, see
 * [MemberRefusal.additionMessageRes]: an addition's words, not a creation's.
 */
class MembershipChangeMessageTest {

    @Test
    fun `members no longer contacts are told as such`() {
        assertEquals(R.plurals.group_members_error_not_contacts, MemberRefusal.NOT_CONTACTS.additionMessageRes())
    }

    @Test
    fun `a changed safety number is told as such`() {
        assertEquals(
            R.plurals.group_members_error_identity_changed,
            MemberRefusal.IDENTITY_CHANGED.additionMessageRes(),
        )
    }

    @Test
    fun `members with nothing to be added with are told as such`() {
        assertEquals(R.plurals.group_members_error_unreachable, MemberRefusal.UNREACHABLE.additionMessageRes())
    }

    @Test
    fun `members who deleted their account are told as such`() {
        assertEquals(R.plurals.group_members_error_deleted, MemberRefusal.DELETED.additionMessageRes())
    }

    /** A creation's says the group was not created. */
    @Test
    fun `an addition is never told in the words of a creation`() {
        MemberRefusal.entries.forEach { refusal ->
            assertNotEquals(refusal.messageRes(), refusal.additionMessageRes())
        }
    }

    @Test
    fun `every refusal is told in words of its own`() {
        assertEquals(
            MemberRefusal.entries.size,
            MemberRefusal.entries.map { it.additionMessageRes() }.toSet().size,
        )
    }
}
