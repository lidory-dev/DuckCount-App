package expense

import database.data.local.entity.PendingExpenseKeyEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "55555555-5555-5555-5555-555555555555"

/** Waited for from Alice when she stopped being a contact. */
private const val OWED = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

/** One Alice put the user on afterwards. */
private const val LISTED_SINCE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

/** Who this device takes keys from, and asks for them, see `OneTimeExpenseKeyRepository.expectedFrom`. */
class ExpectedKeysTest {

    private val contact = ExpectedKeys(isContact = true, awaitedAtRemoval = emptySet())
    private val formerContact = ExpectedKeys(isContact = false, awaitedAtRemoval = setOf(OWED))
    private val neverContact = ExpectedKeys(isContact = false, awaitedAtRemoval = emptySet())

    /** The ordinary case: the key arrives the moment the expense is stored, before any listing shows it. */
    @Test
    fun `a contact's key is taken whether or not this device was waiting for it`() {
        assertTrue(contact.includes(expenseUuid = OWED))
        assertTrue(contact.includes(expenseUuid = LISTED_SINCE))
    }

    @Test
    fun `a former contact's key is taken for an expense awaited when they stopped being one`() {
        assertTrue(formerContact.includes(expenseUuid = OWED))
    }

    /** Something a modified client keeps doing once removed, to show the user an expense they never agreed to. */
    @Test
    fun `a former contact's key to an expense listed since is dropped`() {
        assertFalse(formerContact.includes(expenseUuid = LISTED_SINCE))
    }

    /** Somebody the user sent a contact request to, who never answered it. */
    @Test
    fun `nothing is taken from somebody who never was a contact`() {
        assertFalse(neverContact.includes(expenseUuid = OWED))
    }

    @Test
    fun `a former contact is asked only for the keys awaited from them when they stopped being one`() {
        val asked = formerContact.among(rows = listOf(waiting(LISTED_SINCE), waiting(OWED)))

        assertEquals(listOf(OWED), asked.map { it.expenseUuid })
    }

    @Test
    fun `a contact is asked for every key due from them`() {
        val asked = contact.among(rows = listOf(waiting(LISTED_SINCE), waiting(OWED)))

        assertEquals(listOf(LISTED_SINCE, OWED), asked.map { it.expenseUuid })
    }

    @Test
    fun `somebody who never was a contact is asked for nothing`() {
        assertTrue(neverContact.among(rows = listOf(waiting(LISTED_SINCE), waiting(OWED))).isEmpty())
    }

    private fun waiting(expenseUuid: String) = PendingExpenseKeyEntity(
        expenseUuid = expenseUuid,
        creditorUuid = ALICE,
        firstSeenAt = 0,
        lastRequestedAt = null,
        isAwaitedAtRemoval = expenseUuid == OWED,
    )
}
