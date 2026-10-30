package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import group.GroupExpenseWriteError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told of a write of a group expense that failed, see [toExpenseWriteError]. */
class GroupExpenseWriteErrorMappingTest {

    /** The device now shows the other member's version, which the user checks before writing again. */
    @Test
    fun `another member's edit made first is told as the expense changed meanwhile`() {
        assertEquals(
            ExpenseWriteError.CHANGED_MEANWHILE,
            GroupExpenseWriteError.Conflict.toExpenseWriteError(isOnline = true),
        )
    }

    /** Not the one-time expenses' limit on edits, which the same status once said. */
    @Test
    fun `the expense form is told another member changed the expense meanwhile`() {
        assertEquals(
            ExpenseSaveError.Write(error = ExpenseWriteError.CHANGED_MEANWHILE),
            GroupExpenseWriteError.Conflict.toExpenseSaveError(isOnline = true),
        )
    }

    /** Deleting, or restoring, from a screen opened before a sync read the removal. */
    @Test
    fun `a group the user is no longer in is told as such and not as an expired session`() {
        assertEquals(
            ExpenseWriteError.GROUP_LEFT,
            GroupExpenseWriteError.NotMember.toExpenseWriteError(isOnline = true),
        )
        assertEquals(
            ExpenseSaveError.GroupLeft,
            GroupExpenseWriteError.NotMember.toExpenseSaveError(isOnline = true),
        )
    }

    @Test
    fun `nobody signed in is an expired session`() {
        assertEquals(
            ExpenseWriteError.UNAUTHORIZED,
            GroupExpenseWriteError.NoSession.toExpenseWriteError(isOnline = true),
        )
    }

    /** As for a one-time expense: the identity is where being offline shows first. */
    @Test
    fun `the vault unreachable while opening the identity is told as for a one-time expense`() {
        val error = GroupExpenseWriteError.Identity(MlsIdentityError.Vault(VaultError.Network(NetworkError.UNREACHABLE)))

        assertEquals(ExpenseWriteError.SERVER_UNREACHABLE, error.toExpenseWriteError(isOnline = true))
        assertEquals(ExpenseWriteError.NO_INTERNET, error.toExpenseWriteError(isOnline = false))
    }

    @Test
    fun `the request itself unreachable is told as for a one-time expense`() {
        assertEquals(
            ExpenseWriteError.SERVER_UNREACHABLE,
            GroupExpenseWriteError.Network(NetworkError.UNREACHABLE).toExpenseWriteError(isOnline = true),
        )
        assertEquals(
            ExpenseWriteError.TIMEOUT,
            GroupExpenseWriteError.Network(NetworkError.REQUEST_TIMEOUT).toExpenseWriteError(isOnline = true),
        )
    }

    /** Both are waited out: writing again at once fails the same way. */
    @Test
    fun `a creation the group waits on and the server's limit are told as such`() {
        assertEquals(
            ExpenseWriteError.CREATION_PENDING,
            GroupExpenseWriteError.CreationPending.toExpenseWriteError(isOnline = true),
        )
        assertEquals(
            ExpenseSaveError.Write(error = ExpenseWriteError.RATE_LIMITED),
            GroupExpenseWriteError.RateLimited(retryAfterSeconds = 30).toExpenseSaveError(isOnline = true),
        )
    }

    /** No screen offers it: only its recipient sees a settlement's delete button. */
    @Test
    fun `a settlement written by somebody other than its recipient is unknown`() {
        assertEquals(
            ExpenseWriteError.UNKNOWN,
            GroupExpenseWriteError.SettlementLocked.toExpenseWriteError(isOnline = true),
        )
    }
}
