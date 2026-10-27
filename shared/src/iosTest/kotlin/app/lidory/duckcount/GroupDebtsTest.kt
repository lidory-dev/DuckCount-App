package app.lidory.duckcount

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import database.domain.GroupMember
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * The debts of a group whose creator, Alice, removed Carol after paying thirty euros for the three
 * of them: the expense still names Carol, whom the library refuses an expense for unless she is
 * one of the group's users.
 */
class GroupDebtsTest {

    private val members = listOf(ALICE, BOB).map { GroupMember(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) }

    private val expenses = listOf(
        ExpenseWithBeneficiaries(
            uuid = "expense",
            title = "Groceries",
            timestamp = "2026-10-26T12:00:00Z",
            description = "",
            amount = "30.00",
            creditorUuid = ALICE,
            currency = CurrencyCatalog.defaultCurrency,
            groupUuid = GROUP,
            beneficiaries = listOf(ALICE, BOB, CAROL).map {
                Beneficiary(expenseUuid = "expense", userUuid = it, share = "10.00", isShareFixed = false)
            },
        ),
    )

    @Test
    fun `a former member an expense names keeps their balance`() {
        val debts = calculateNetDebtsPerUser(
            currency = CurrencyCatalog.defaultCurrency,
            members = members,
            expenses = expenses,
        )

        assertEquals(mapOf(ALICE to 20.0, BOB to -10.0, CAROL to -10.0), debts.mapValues { it.value.toDouble() })
    }

    @Test
    fun `a former member an expense names is settled up with`() {
        val transactions = getTransactionForGroup(
            currency = CurrencyCatalog.defaultCurrency,
            members = members,
            expenses = expenses,
        )

        assertEquals(
            setOf(Triple(BOB, ALICE, 10.0), Triple(CAROL, ALICE, 10.0)),
            transactions.mapTo(HashSet()) { Triple(it.debtorUuid, it.creditorUuid, it.amount.toDouble()) },
        )
    }
}
