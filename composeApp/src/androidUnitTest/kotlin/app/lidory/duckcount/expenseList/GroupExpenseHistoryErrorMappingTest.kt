package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.model.ExpenseHistoryError
import group.GroupExpenseHistoryError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told of the history of a group expense that could not be read, see [toExpenseHistoryError]. */
class GroupExpenseHistoryErrorMappingTest {

    /** Asked for with the server out of reach since the app opened: the identity is where it shows first. */
    @Test
    fun `the vault unreachable while opening the identity is told as the server unreachable`() {
        val error = GroupExpenseHistoryError.Identity(MlsIdentityError.Vault(VaultError.Network(NetworkError.UNREACHABLE)))

        assertEquals(ExpenseHistoryError.SERVER_UNREACHABLE, error.toExpenseHistoryError(isOnline = true))
        assertEquals(ExpenseHistoryError.NO_INTERNET, error.toExpenseHistoryError(isOnline = false))
    }

    @Test
    fun `an identity that does not open for another reason is unknown`() {
        assertEquals(
            ExpenseHistoryError.UNKNOWN,
            GroupExpenseHistoryError.Identity(MlsIdentityError.Crypto).toExpenseHistoryError(isOnline = true),
        )
    }

    @Test
    fun `the request itself timing out is told as such`() {
        assertEquals(
            ExpenseHistoryError.TIMEOUT,
            GroupExpenseHistoryError.Network(NetworkError.REQUEST_TIMEOUT).toExpenseHistoryError(isOnline = true),
        )
    }
}
