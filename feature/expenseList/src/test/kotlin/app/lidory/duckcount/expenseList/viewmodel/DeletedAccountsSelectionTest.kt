package app.lidory.duckcount.expenseList.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who stays selected once the server refused an expense for naming people who deleted their
 * account, see [withoutDeletedAccounts].
 */
class DeletedAccountsSelectionTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"
    private val dave = "dave"

    @Test
    fun `everybody the refusal names leaves the selection`() {
        assertEquals(
            listOf(alice, dave),
            withoutDeletedAccounts(beneficiaryUuids = listOf(alice, bob, carol, dave), deletedUserUuids = listOf(carol, bob)).toList(),
        )
    }

    /**
     * Carol deleted her account after the expense named her, and the edit keeps her, so the server
     * names only Bob, whom it adds: she stays.
     */
    @Test
    fun `a beneficiary the edit keeps stays even if they deleted their account`() {
        assertEquals(
            setOf(alice, carol),
            withoutDeletedAccounts(beneficiaryUuids = listOf(alice, carol, bob), deletedUserUuids = listOf(bob)),
        )
    }
}
