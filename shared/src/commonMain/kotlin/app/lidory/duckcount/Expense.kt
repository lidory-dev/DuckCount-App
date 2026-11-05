package app.lidory.duckcount

import uniffi.lib_duckcount.Beneficiary
import uniffi.lib_duckcount.Currency
import uniffi.lib_duckcount.ExpenseException
import uniffi.lib_duckcount.canonicalAmount
import uniffi.lib_duckcount.computeBeneficiaries
import uniffi.lib_duckcount.validateExpense

fun validateExpense(
    amount: String,
    currency: database.domain.Currency,
    beneficiaries: List<database.domain.Beneficiary>,
): ExpenseError? {
    try {
        validateExpense(
            amount = amount,
            currency = Currency(name = currency.name, symbol = currency.symbol, hasSubUnit = currency.hasSubunits),
            beneficiaries = beneficiaries.map { beneficiary ->
                Beneficiary(
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                    sharePercentage = beneficiary.sharePercentage,
                    shareWeight = beneficiary.shareWeight,
                )
            },
        )
        return null

    } catch (e: Exception) {
        return when (e) {
            is ExpenseException.SharesBelowTotalAmount -> ExpenseError.SHARES_BELOW_TOTAL_AMOUNT
            is ExpenseException.SharesExceedTotalAmount -> ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT
            is ExpenseException.NonPositiveShare -> ExpenseError.NON_POSITIVE_SHARE
            is ExpenseException.NoBeneficiaries -> ExpenseError.NO_BENEFICIARIES
            is ExpenseException.TooManyBeneficiaries -> ExpenseError.TOO_MANY_BENEFICIARIES
            else -> ExpenseError.OTHER_KNOWN_ERROR
        }
    }
}

/**
 * [amount] written the one way the library takes an amount and hands one out, `12` in a [currency]
 * without a subunit and `12.00` in one with, or null when the library cannot read it as an amount
 * in [currency].
 */
fun canonicalAmount(amount: String, currency: database.domain.Currency): String? =
    try {
        canonicalAmount(
            amount = amount,
            currency = Currency(name = currency.name, symbol = currency.symbol, hasSubUnit = currency.hasSubunits),
        )
    } catch (_: ExpenseException) {
        null
    }

fun computeBeneficiaries(
    amount: String,
    currency: Currency,
    beneficiaryUserUuids: List<String>,
    fixedShares: Map<String, String>,
    sharePercentages: Map<String, String>,
    shareWeights: Map<String, String>,
): List<Beneficiary> {
    return try {
        computeBeneficiaries(
            amount = amount,
            currency = currency,
            beneficiaryUserUuids = beneficiaryUserUuids,
            fixedShares = fixedShares,
            sharePercentages = sharePercentages,
            shareWeights = shareWeights,
        )
    } catch (_: ExpenseException) {
        // Shares can't be computed yet (e.g. a blank/invalid amount or shares that
        // don't add up while the user is still editing). Don't crash: keep the
        // selected beneficiaries with their fixed shares, percentages and weights so
        // the UI stays intact, and let a later valid input recompute the shares.
        beneficiaryUserUuids.map { userUuid ->
            Beneficiary(
                userUuid = userUuid,
                share = fixedShares[userUuid] ?: "",
                sharePercentage = sharePercentages[userUuid],
                shareWeight = shareWeights[userUuid],
            )
        }
    }
}
