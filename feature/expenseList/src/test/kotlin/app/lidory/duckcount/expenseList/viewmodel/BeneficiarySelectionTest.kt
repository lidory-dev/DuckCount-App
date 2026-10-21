package app.lidory.duckcount.expenseList.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Selecting beneficiaries one by one never goes past the cap, see [toggledSelection]. */
class BeneficiarySelectionTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"

    @Test
    fun `selecting someone below the cap adds them`() {
        assertEquals(setOf(alice, bob), toggledSelection(selected = setOf(alice), uuid = bob, max = 2))
    }

    @Test
    fun `selecting someone at the cap does nothing`() {
        assertEquals(setOf(alice, bob), toggledSelection(selected = setOf(alice, bob), uuid = carol, max = 2))
    }

    @Test
    fun `unselecting someone at the cap takes them out`() {
        assertEquals(setOf(alice), toggledSelection(selected = setOf(alice, bob), uuid = bob, max = 2))
    }
}
