package app.lidory.duckcount.groupList.viewmodel

import app.lidory.duckcount.groupList.model.Transaction
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import org.junit.Assert.assertEquals
import org.junit.Test

/** How what settles a group up names its debtors and creditors, see [namedTransactions], as names are read. */
class SettleUpNamesTest {

    private val transaction = Transaction(debtorUuid = "bob", creditorUuid = "alice", amount = "10")

    @Test
    fun `a name read after the transactions showed shows`() {
        val names = FakeNames("alice" to "Alice")

        collecting(namedTransactions(transactions = listOf(transaction), nameOf = names::of)) { emissions ->
            assertEquals(
                listOf(
                    TransactionWithFullName(
                        debtorUuid = "bob",
                        debtorFullName = null,
                        creditorUuid = "alice",
                        creditorFullName = "Alice",
                        amount = "10",
                    ),
                ),
                emissions.receive(),
            )

            names["bob"] = "Bob"

            assertEquals(listOf("Bob"), emissions.receive().map { it.debtorFullName })
        }
    }

    /** Combining the names of nobody would never tell anything, and the tab would load forever. */
    @Test
    fun `a group all settled up is told so at once`() {
        collecting(namedTransactions(transactions = emptyList(), nameOf = FakeNames()::of)) { emissions ->
            assertEquals(emptyList<TransactionWithFullName>(), emissions.receive())
        }
    }
}
