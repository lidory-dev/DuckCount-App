package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.MemberRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sheet of a member of the group offers, see [memberSheetActions]: what where the user
 * stands with them calls for, a request to them only from a group that vouches for who they are,
 * then their removal for whoever may remove them; and where it tells the user stands with them once a
 * request sent from it is out, see [isRequestOut] and [shownRelation].
 */
class MemberSheetTest {

    @Test
    fun `the sheet of a contact offers their profile`() {
        assertEquals(
            listOf(MemberSheetAction.VIEW_PROFILE),
            memberSheetActions(relation = MemberRelation.CONTACT, canVouchForMembers = true, isRemovable = false),
        )
    }

    @Test
    fun `the sheet of a member who asked the user offers to answer them`() {
        assertEquals(
            listOf(MemberSheetAction.ANSWER_REQUEST),
            memberSheetActions(
                relation = MemberRelation.REQUEST_RECEIVED,
                canVouchForMembers = true,
                isRemovable = false,
            ),
        )
    }

    @Test
    fun `the sheet of a member the user asked tells the request is sent`() {
        assertEquals(
            listOf(MemberSheetAction.REQUEST_SENT),
            memberSheetActions(relation = MemberRelation.REQUEST_SENT, canVouchForMembers = true, isRemovable = false),
        )
    }

    @Test
    fun `the sheet of anybody else offers to send them a request`() {
        assertEquals(
            listOf(MemberSheetAction.SEND_REQUEST),
            memberSheetActions(relation = MemberRelation.NONE, canVouchForMembers = true, isRemovable = false),
        )
    }

    /**
     * One the user was removed from, brought back after a log-out, or whose creator is blocked:
     * nothing else on this device vouches for who they are.
     */
    @Test
    fun `no request is offered from a group that does not vouch for its members`() {
        assertEquals(
            emptyList<MemberSheetAction>(),
            memberSheetActions(relation = MemberRelation.NONE, canVouchForMembers = false, isRemovable = false),
        )
    }

    @Test
    fun `a group that does not vouch for its members still offers what needs no group`() {
        assertEquals(
            listOf(MemberSheetAction.VIEW_PROFILE),
            memberSheetActions(relation = MemberRelation.CONTACT, canVouchForMembers = false, isRemovable = false),
        )
        assertEquals(
            listOf(MemberSheetAction.ANSWER_REQUEST),
            memberSheetActions(
                relation = MemberRelation.REQUEST_RECEIVED,
                canVouchForMembers = false,
                isRemovable = false,
            ),
        )
        assertEquals(
            listOf(MemberSheetAction.REQUEST_SENT),
            memberSheetActions(relation = MemberRelation.REQUEST_SENT, canVouchForMembers = false, isRemovable = false),
        )
    }

    @Test
    fun `the removal comes after what the relation calls for`() {
        MemberRelation.entries.forEach { relation ->
            val actions = memberSheetActions(relation = relation, canVouchForMembers = true, isRemovable = true)

            assertEquals(2, actions.size)
            assertEquals(MemberSheetAction.REMOVE, actions.last())
        }
    }

    @Test
    fun `a member who cannot be removed is offered no removal`() {
        MemberRelation.entries.forEach { relation ->
            assertFalse(
                MemberSheetAction.REMOVE in memberSheetActions(
                    relation = relation,
                    canVouchForMembers = true,
                    isRemovable = false,
                ),
            )
        }
    }

    /** The database tells the request stored a moment after it returns. */
    @Test
    fun `a request the server took is told as sent before the database tells it`() {
        assertEquals(MemberRelation.REQUEST_SENT, shownRelation(relation = MemberRelation.NONE, isRequestTaken = true))
    }

    @Test
    fun `where the user stands with a member is told as read with no request taken`() {
        MemberRelation.entries.forEach { relation ->
            assertEquals(relation, shownRelation(relation = relation, isRequestTaken = false))
        }
    }

    /** They accepted it already, say, or had asked the user meanwhile. */
    @Test
    fun `a request taken never hides what the database tells since`() {
        listOf(MemberRelation.CONTACT, MemberRelation.REQUEST_RECEIVED, MemberRelation.REQUEST_SENT).forEach { relation ->
            assertEquals(relation, shownRelation(relation = relation, isRequestTaken = true))
        }
    }

    @Test
    fun `a request the server took is out`() {
        assertTrue(isRequestOut(error = null))
    }

    /** Offered again, it would spend another of their key packages for the same refusal. */
    @Test
    fun `a request refused as already pending is out`() {
        assertTrue(isRequestOut(error = ContactRequestError.ALREADY_PENDING))
    }

    @Test
    fun `a request that failed otherwise is not out`() {
        ContactRequestError.entries.filter { it != ContactRequestError.ALREADY_PENDING }.forEach { error ->
            assertFalse(isRequestOut(error = error))
        }
    }
}
