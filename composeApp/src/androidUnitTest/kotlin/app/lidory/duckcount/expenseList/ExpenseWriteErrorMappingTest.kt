package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import expense.OneTimeExpenseWriteError
import kotlinx.coroutines.runBlocking
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told of a one-time expense write that failed, see [toExpenseWriteError]. */
class ExpenseWriteErrorMappingTest {

    private fun identityVaultUnreachable(error: NetworkError) =
        OneTimeExpenseWriteError.Identity(MlsIdentityError.Vault(VaultError.Network(error)))

    /**
     * Marking an expense as settled while the server has been out of reach since the app opened:
     * opening the identity reads the vault first, and fails before the mark is sent.
     */
    @Test
    fun `the vault unreachable while opening the identity online is the server unreachable`() {
        assertEquals(
            ExpenseWriteError.SERVER_UNREACHABLE,
            identityVaultUnreachable(NetworkError.UNREACHABLE).toExpenseWriteError(isOnline = true),
        )
    }

    @Test
    fun `the vault unreachable while opening the identity offline is no internet`() {
        assertEquals(
            ExpenseWriteError.NO_INTERNET,
            identityVaultUnreachable(NetworkError.UNREACHABLE).toExpenseWriteError(isOnline = false),
        )
    }

    @Test
    fun `the vault timing out while opening the identity is a timeout`() {
        assertEquals(
            ExpenseWriteError.TIMEOUT,
            identityVaultUnreachable(NetworkError.REQUEST_TIMEOUT).toExpenseWriteError(isOnline = true),
        )
    }

    @Test
    fun `an identity with nobody signed in is an expired session`() {
        assertEquals(
            ExpenseWriteError.UNAUTHORIZED,
            OneTimeExpenseWriteError.Identity(MlsIdentityError.MissingSession).toExpenseWriteError(isOnline = true),
        )
    }

    @Test
    fun `an identity the library refuses to open is unknown`() {
        assertEquals(
            ExpenseWriteError.UNKNOWN,
            OneTimeExpenseWriteError.Identity(MlsIdentityError.Crypto).toExpenseWriteError(isOnline = true),
        )
    }

    /** The form opens the identity before sending the expense, as marking it as settled does. */
    @Test
    fun `the expense form is told the server is unreachable while opening the identity`() {
        assertEquals(
            ExpenseSaveError.Write(error = ExpenseWriteError.SERVER_UNREACHABLE),
            runBlocking {
                identityVaultUnreachable(NetworkError.UNREACHABLE)
                    .toExpenseSaveError(isOnline = true, fullNameOf = { null })
            },
        )
    }

    @Test
    fun `the request itself unreachable online is the server unreachable`() {
        assertEquals(
            ExpenseWriteError.SERVER_UNREACHABLE,
            OneTimeExpenseWriteError.Network(NetworkError.UNREACHABLE).toExpenseWriteError(isOnline = true),
        )
    }
}
