package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.TransactionWithFullName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whose each debt of a group is for the user, see [sideFor]: the settle-up list shows every debt in
 * the group, and words each one for the user reading it.
 */
class DebtSideTest {

    private val bobOwesAlice = TransactionWithFullName(
        debtorUuid = "bob",
        debtorFullName = "Bob",
        creditorUuid = "alice",
        creditorFullName = "Alice",
        amount = "10",
    )

    /** Theirs to record paid back, which only the creditor can. */
    @Test
    fun `a debt owed to the user is owed to them`() {
        assertEquals(DebtSide.OWED_TO_USER, bobOwesAlice.sideFor(userUuid = "alice"))
    }

    @Test
    fun `a debt the user owes is owed by them`() {
        assertEquals(DebtSide.OWED_BY_USER, bobOwesAlice.sideFor(userUuid = "bob"))
    }

    /** Neither "you owe" nor "owes you": somebody else owes somebody else. */
    @Test
    fun `a debt between two other members is between others`() {
        assertEquals(DebtSide.BETWEEN_OTHERS, bobOwesAlice.sideFor(userUuid = "carol"))
    }
}
