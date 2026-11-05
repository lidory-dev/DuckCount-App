package app.lidory.duckcount

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import uniffi.lib_duckcount.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ALICE = "00000000-0000-4000-8000-000000000001"
private const val BOB = "00000000-0000-4000-8000-000000000002"
private const val CHARLIE = "00000000-0000-4000-8000-000000000003"

/**
 * A share set as a weight as the app has the library work it out and check it: what it comes to,
 * the weight stated for everybody else sharing what is left, and what is left of the form when a
 * weight cannot be read yet.
 */
class ShareWeightTest {

    private val euro = Currency(name = "Euro", symbol = "€", hasSubUnit = true)

    private fun compute(weights: Map<String, String>) =
        computeBeneficiaries(
            amount = "100.00",
            currency = euro,
            beneficiaryUserUuids = listOf(ALICE, BOB, CHARLIE),
            fixedShares = emptyMap(),
            sharePercentages = emptyMap(),
            shareWeights = weights,
        )

    @Test
    fun `two parts for Alice and one each for the others of 100 is 50 and 25 each`() {
        assertEquals(listOf(50.0, 25.0, 25.0), compute(mapOf(ALICE to "2")).map { it.share.toDouble() })
    }

    @Test
    fun `a weight is stated for everybody sharing what is left once anybody has one`() {
        assertEquals(
            mapOf(ALICE to "2", BOB to "1", CHARLIE to "1"),
            compute(mapOf(ALICE to "2")).associate { it.userUuid to it.shareWeight },
        )
    }

    @Test
    fun `no weight is stated when nobody has one`() {
        assertEquals(listOf(null, null, null), compute(emptyMap()).map { it.shareWeight })
    }

    /** The form goes on with them as they are, its shares blank until the weight can be read. */
    @Test
    fun `a weight that cannot be read yet stays on the beneficiary`() {
        val beneficiaries = compute(mapOf(ALICE to "2."))

        assertEquals(mapOf(ALICE to "2.", BOB to null, CHARLIE to null), beneficiaries.associate { it.userUuid to it.shareWeight })
        assertEquals(listOf("", "", ""), beneficiaries.map { it.share })
    }

    @Test
    fun `what weights come to is valid`() {
        assertNull(validated(alicesShare = "50.00", bobsShare = "25.00", charliesShare = "25.00"))
    }

    @Test
    fun `shares their weights do not come to are refused`() {
        assertEquals(
            ExpenseError.OTHER_KNOWN_ERROR,
            validated(alicesShare = "60.00", bobsShare = "20.00", charliesShare = "20.00"),
        )
    }

    private fun validated(alicesShare: String, bobsShare: String, charliesShare: String): ExpenseError? =
        validateExpense(
            amount = "100.00",
            currency = CurrencyCatalog.defaultCurrency,
            beneficiaries = listOf(
                Beneficiary(expenseUuid = "", userUuid = ALICE, share = alicesShare, isShareFixed = true, shareWeight = "2"),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = bobsShare, isShareFixed = false, shareWeight = "1"),
                Beneficiary(expenseUuid = "", userUuid = CHARLIE, share = charliesShare, isShareFixed = false, shareWeight = "1"),
            ),
        )
}
