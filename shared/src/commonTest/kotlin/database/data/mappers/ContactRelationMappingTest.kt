package database.data.mappers

import contact.ContactRelation
import database.data.local.entity.ContactStanding
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the user stands with somebody, from what the tables say of them, see [ContactRelation]. */
class ContactRelationMappingTest {

    @Test
    fun `somebody the tables say nothing of is no relation`() {
        assertEquals(ContactRelation.NONE, standing().toContactRelation())
    }

    @Test
    fun `a contact is a contact`() {
        assertEquals(ContactRelation.CONTACT, standing(isContact = true).toContactRelation())
    }

    @Test
    fun `a request of theirs waiting for the user is a request received`() {
        assertEquals(ContactRelation.REQUEST_RECEIVED, standing(hasRequestFrom = true).toContactRelation())
    }

    @Test
    fun `a request of the user's out to them is a request sent`() {
        assertEquals(ContactRelation.REQUEST_SENT, standing(hasRequestTo = true).toContactRelation())
    }

    /** As an acceptance leaves them for a moment, the contact added before their requests go. */
    @Test
    fun `a contact with requests either way left is a contact`() {
        assertEquals(
            ContactRelation.CONTACT,
            standing(isContact = true, hasRequestFrom = true, hasRequestTo = true).toContactRelation(),
        )
    }

    /** Accepting theirs is what the user can do, and it takes the user's own request along. */
    @Test
    fun `requests crossing are a request received`() {
        assertEquals(
            ContactRelation.REQUEST_RECEIVED,
            standing(hasRequestFrom = true, hasRequestTo = true).toContactRelation(),
        )
    }

    @Test
    fun `a contact with a request of the user's left is a contact`() {
        assertEquals(ContactRelation.CONTACT, standing(isContact = true, hasRequestTo = true).toContactRelation())
    }

    /** Most writes to the tables leave the relation as it was. */
    @Test
    fun `each relation is told once until it changes`() = runBlocking<Unit> {
        val relations = flowOf(
            standing(),
            standing(),
            standing(hasRequestTo = true),
            standing(hasRequestFrom = true, hasRequestTo = true),
            standing(hasRequestFrom = true),
            standing(isContact = true, hasRequestFrom = true),
            standing(isContact = true),
        ).toContactRelations().toList()

        assertEquals(
            listOf(
                ContactRelation.NONE,
                ContactRelation.REQUEST_SENT,
                ContactRelation.REQUEST_RECEIVED,
                ContactRelation.CONTACT,
            ),
            relations,
        )
    }

    private fun standing(isContact: Boolean = false, hasRequestFrom: Boolean = false, hasRequestTo: Boolean = false) =
        ContactStanding(isContact = isContact, hasRequestFrom = hasRequestFrom, hasRequestTo = hasRequestTo)
}
