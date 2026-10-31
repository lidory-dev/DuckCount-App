package database.data.mappers

import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupExpenseDeletionEntity
import database.domain.CurrencyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EXPENSE = "8d1e5823-c00c-83b7-8212-500089e3b975"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

/**
 * Who the screens name as having deleted an expense of a group, see
 * [database.domain.ExpenseWithBeneficiaries.deletedByUuid]: the author of its deletion flag, while
 * the flag deletes it. A restore keeps the flag, with whoever restored it as its author.
 */
class GroupExpenseDeletionMappingTest {

    @Test
    fun `an expense nobody deleted names nobody as its deleter`() {
        val expense = expense(deletion = null).toExpenseWithBeneficiaries()

        assertFalse(expense.isDeleted)
        assertNull(expense.deletedByUuid)
    }

    @Test
    fun `an expense whose flag deletes it names the flag's author`() {
        val expense = expense(deletion = flag(deletionRevision = 1, isDeleted = true)).toExpenseWithBeneficiaries()

        assertTrue(expense.isDeleted)
        assertEquals(CAROL, expense.deletedByUuid)
    }

    /** Carol restored it: she set the flag, which deletes nothing. */
    @Test
    fun `an expense whose flag restores it names nobody as its deleter`() {
        val expense = expense(deletion = flag(deletionRevision = 2, isDeleted = false)).toExpenseWithBeneficiaries()

        assertFalse(expense.isDeleted)
        assertNull(expense.deletedByUuid)
    }

    private fun flag(deletionRevision: Long, isDeleted: Boolean) = GroupExpenseDeletionEntity(
        expenseUuid = EXPENSE,
        deletionRevision = deletionRevision,
        isDeleted = isDeleted,
        authorUuid = CAROL,
    )

    /** The expense of a group as it is stored, deleted as [deletion] says, as the flag sets it. */
    private fun expense(deletion: GroupExpenseDeletionEntity?) = ExpenseWithBeneficiariesEntity(
        expense = ExpenseEntity(
            uuid = EXPENSE,
            title = "Pizzas",
            timestamp = "2026-10-12T09:00:00",
            description = "",
            amount = "20",
            creditorUuid = "01234567-89ab-4cde-8f01-23456789abcd",
            currency = CurrencyCatalog.defaultCurrency,
            groupUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            isDeleted = deletion?.isDeleted ?: false,
            revision = 1,
        ),
        beneficiaries = emptyList(),
        deletion = deletion,
    )
}
