package app.lidory.duckcount.expenseList.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the form names who deleted their account, see [ExpenseSaveError.BeneficiariesDeleted]:
 * by their names, see [fullNames], listed as a sentence lists them, see [listedNames].
 */
class DeletedBeneficiariesMessageTest {

    private val separator = ", "
    private val english = "%1\$s and %2\$s"
    private val french = "%1\$s et %2\$s"

    @Test
    fun `one name is said alone`() {
        assertEquals("Alice", listedNames(names = listOf("Alice"), separator = separator, lastPair = english))
    }

    @Test
    fun `two names are joined by the language's and`() {
        assertEquals("Alice and Bob", listedNames(names = listOf("Alice", "Bob"), separator = separator, lastPair = english))
        assertEquals("Alice et Bob", listedNames(names = listOf("Alice", "Bob"), separator = separator, lastPair = french))
    }

    @Test
    fun `more names are separated until the last two`() {
        assertEquals(
            "Alice, Bob and Carol",
            listedNames(names = listOf("Alice", "Bob", "Carol"), separator = separator, lastPair = english),
        )
    }

    @Test
    fun `everybody is named in the order of the refusal`() {
        val refusal = ExpenseSaveError.BeneficiariesDeleted(
            beneficiaries = listOf(
                DeletedBeneficiary(userUuid = "bob", fullName = "Bob"),
                DeletedBeneficiary(userUuid = "alice", fullName = "Alice"),
            ),
        )

        assertEquals(listOf("Bob", "Alice"), refusal.fullNames())
    }

    /** Leaving them out would say fewer deleted their account than did. */
    @Test
    fun `nobody is named when one has no name on this device`() {
        val refusal = ExpenseSaveError.BeneficiariesDeleted(
            beneficiaries = listOf(
                DeletedBeneficiary(userUuid = "alice", fullName = "Alice"),
                DeletedBeneficiary(userUuid = "bob", fullName = null),
            ),
        )

        assertNull(refusal.fullNames())
    }
}
