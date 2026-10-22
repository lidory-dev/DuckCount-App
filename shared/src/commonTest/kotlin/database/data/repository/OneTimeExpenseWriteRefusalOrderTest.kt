package database.data.repository

import database.data.remote.WriteOneTimeExpenseError
import expense.OneTimeExpenseWriteError
import util.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ALICE = "a25df1b3-4124-4164-a2db-1d37da9e8d23"
private const val BOB = "6b74604f-9925-45aa-aa8f-49546ff44e44"
private const val CAROL = "3cf93fad-a390-4104-a046-679a62e7ce04"

/**
 * What the server's refusal of a write of a one-time expense says to whoever made it, see
 * [toOneTimeExpenseWriteError].
 */
class OneTimeExpenseWriteRefusalOrderTest {

    /** The server sorts them by UUID, which says nothing to the user. */
    @Test
    fun `who deleted their account is named in the order the expense names them`() {
        val refusal = WriteOneTimeExpenseError.BeneficiariesDeleted(userUuids = listOf(CAROL, ALICE))

        assertEquals(
            OneTimeExpenseWriteError.BeneficiariesDeleted(userUuids = listOf(ALICE, CAROL)),
            refusal.toOneTimeExpenseWriteError(beneficiaryUuids = listOf(ALICE, BOB, CAROL)),
        )
    }

    @Test
    fun `any other refusal is the network's`() {
        assertEquals(
            OneTimeExpenseWriteError.Network(NetworkError.CONFLICT),
            WriteOneTimeExpenseError.Network(NetworkError.CONFLICT).toOneTimeExpenseWriteError(beneficiaryUuids = listOf(ALICE)),
        )
    }
}
