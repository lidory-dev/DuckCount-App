package app.lidory.duckcount.expenseList.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the form names who deleted their account, see [ExpenseSaveError.BeneficiariesDeleted]:
 * by their names, see [fullNames], listed as a sentence lists them, see
 * [app.lidory.duckcount.android.ui.text.listedNames].
 */
class DeletedBeneficiariesMessageTest {

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
