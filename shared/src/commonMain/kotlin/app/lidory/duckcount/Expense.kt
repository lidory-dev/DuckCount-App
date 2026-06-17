package app.lidory.duckcount

import uniffi.lib_duckcount.Beneficiary
import uniffi.lib_duckcount.Currency
import uniffi.lib_duckcount.ExpenseException
import uniffi.lib_duckcount.validateExpense

fun validateExpense(
    amount: String,
    currency: database.currency.Currency,
    beneficiaries: List<database.beneficiary.Beneficiary>,
): ExpenseError? {
    try {
        validateExpense(
            amount = amount,
            currency = Currency(name = currency.name, symbol = currency.symbol, hasSubUnit = currency.hasSubunits),
            beneficiaries = beneficiaries.map { beneficiary ->
                Beneficiary(userId = beneficiary.userUuid, share = beneficiary.share)
            },
        )
        return null

    } catch (e: Exception) {
        return when (e) {
            is ExpenseException.SharesBelowTotalAmount -> ExpenseError.SHARES_BELOW_TOTAL_AMOUNT
            is ExpenseException.SharesExceedTotalAmount -> ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT
            is ExpenseException.NonPositiveShare -> ExpenseError.NON_POSITIVE_SHARE
            is ExpenseException.NoBeneficiaries -> ExpenseError.NO_BENEFICIARIES
            else -> ExpenseError.OTHER_KNOWN_ERROR
        }
    }
}
