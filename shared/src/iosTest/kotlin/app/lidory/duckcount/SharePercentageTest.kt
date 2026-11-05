package app.lidory.duckcount

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import uniffi.lib_duckcount.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ALICE = "00000000-0000-4000-8000-000000000001"
private const val BOB = "00000000-0000-4000-8000-000000000002"

/**
 * A share set as a percentage as the app has the library work it out and check it: what it comes
 * to, and what is left of the form when the percentages do not make up the amount yet.
 */
class SharePercentageTest {

    private val euro = Currency(name = "Euro", symbol = "€", hasSubUnit = true)

    @Test
    fun `a percentage is worked out with what it was set as`() {
        val beneficiaries = computeBeneficiaries(
            amount = "80.00",
            currency = euro,
            beneficiaryUserUuids = listOf(ALICE, BOB),
            fixedShares = emptyMap(),
            sharePercentages = mapOf(ALICE to "25"),
        )

        assertEquals(mapOf(ALICE to "25", BOB to null), beneficiaries.associate { it.userUuid to it.sharePercentage })
        assertEquals(listOf(20.0, 60.0), beneficiaries.map { it.share.toDouble() })
    }

    /** The form goes on with them as they are, its shares blank until they do. */
    @Test
    fun `percentages that do not make up the amount yet stay on the beneficiaries`() {
        val beneficiaries = computeBeneficiaries(
            amount = "80.00",
            currency = euro,
            beneficiaryUserUuids = listOf(ALICE, BOB),
            fixedShares = emptyMap(),
            sharePercentages = mapOf(ALICE to "25", BOB to "50"),
        )

        assertEquals(mapOf(ALICE to "25", BOB to "50"), beneficiaries.associate { it.userUuid to it.sharePercentage })
        assertEquals(listOf("", ""), beneficiaries.map { it.share })
    }

    @Test
    fun `what a percentage comes to is valid`() {
        assertNull(validated(alicesShare = "20.00", alicesPercentage = "25", bobsShare = "60.00"))
    }

    @Test
    fun `a share its percentage does not come to is refused`() {
        assertEquals(
            ExpenseError.OTHER_KNOWN_ERROR,
            validated(alicesShare = "40.00", alicesPercentage = "25", bobsShare = "40.00"),
        )
    }

    @Test
    fun `a percentage the library cannot read is refused`() {
        assertEquals(
            ExpenseError.OTHER_KNOWN_ERROR,
            validated(alicesShare = "20.00", alicesPercentage = "a quarter", bobsShare = "60.00"),
        )
    }

    private fun validated(alicesShare: String, alicesPercentage: String, bobsShare: String): ExpenseError? =
        validateExpense(
            amount = "80.00",
            currency = CurrencyCatalog.defaultCurrency,
            beneficiaries = listOf(
                Beneficiary(
                    expenseUuid = "",
                    userUuid = ALICE,
                    share = alicesShare,
                    isShareFixed = true,
                    sharePercentage = alicesPercentage,
                ),
                Beneficiary(expenseUuid = "", userUuid = BOB, share = bobsShare, isShareFixed = false),
            ),
        )
}
