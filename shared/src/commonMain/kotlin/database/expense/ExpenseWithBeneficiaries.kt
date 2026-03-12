package database.expense

import database.beneficiary.Beneficiary
import database.currency.Currency

data class ExpenseWithBeneficiaries(
    val uuid: String,
    val title: String,
    val timestamp: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isRefund: Boolean,
    val currency: Currency,
    val beneficiaries: List<Beneficiary>,
)
