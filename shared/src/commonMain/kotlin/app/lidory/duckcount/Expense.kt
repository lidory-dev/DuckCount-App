package app.lidory.duckcount

import uniffi.lib_duckcount.Beneficiary
import uniffi.lib_duckcount.Currency
import uniffi.lib_duckcount.validateExpense

fun validateExpense(
    amount: String,
    currency: database.currency.Currency,
    beneficiaries: List<database.beneficiary.Beneficiary>,
) = validateExpense(
    amount = amount,
    currency = Currency(name = currency.name, symbol = currency.symbol, hasSubUnit = currency.hasSubunits),
    beneficiaries = beneficiaries.map { beneficiary ->
        Beneficiary(userId = beneficiary.userUuid, share = beneficiary.share)
    },
)