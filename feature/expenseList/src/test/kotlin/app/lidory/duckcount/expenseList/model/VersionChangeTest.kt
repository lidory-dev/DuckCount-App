package app.lidory.duckcount.expenseList.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** What each version of an expense did to it, as its history tells with who did it, see [changeFrom]. */
class VersionChangeTest {

    @Test
    fun `the version created is the creation`() {
        assertEquals(VersionChange.CREATION, version(revision = 1, isCreation = true).changeFrom(earlier = null))
    }

    @Test
    fun `a later version is an edit`() {
        assertEquals(VersionChange.EDIT, version(revision = 2).changeFrom(earlier = version(revision = 1, isCreation = true)))
    }

    @Test
    fun `a version saying the expense is deleted is its deletion`() {
        assertEquals(VersionChange.DELETION, version(revision = 2, isDeleted = true).changeFrom(earlier = version(revision = 1)))
    }

    @Test
    fun `a version after a deletion saying the expense is not deleted is its restoration`() {
        assertEquals(
            VersionChange.RESTORATION,
            version(revision = 3).changeFrom(earlier = version(revision = 2, isDeleted = true)),
        )
    }

    /** Older than the versions the server keeps: whether it followed a deletion is not known. */
    @Test
    fun `the earliest version kept that is no creation is an edit`() {
        assertEquals(VersionChange.EDIT, version(revision = 11).changeFrom(earlier = null))
    }

    private fun version(revision: Long, isCreation: Boolean = false, isDeleted: Boolean = false) = ExpenseVersion(
        revision = revision,
        expense = ExpenseWithBeneficiaries(
            uuid = "expense",
            timestamp = "2026-10-30T20:00:00",
            title = "Dinner",
            description = "",
            amount = "40.00",
            creditorUuid = "bob",
            isSettlement = false,
            currency = Currency(name = "EUR", symbol = "€", hasSubunits = true),
            beneficiaries = listOf(Beneficiary(expenseUuid = "expense", userUuid = "alice", share = "40.00")),
            groupUuid = "group",
            isDeleted = isDeleted,
        ),
        authorUuid = "bob",
        isCreation = isCreation,
        madeAt = null,
    )
}
