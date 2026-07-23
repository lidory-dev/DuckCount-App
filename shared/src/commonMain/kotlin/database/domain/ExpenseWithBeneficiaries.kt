package database.domain

data class ExpenseWithBeneficiaries(
    val uuid: String,
    val title: String,
    val timestamp: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isSettlement: Boolean,
    val currency: Currency,
    val groupUuid: String? = null,
    val isDeleted: Boolean = false,
    val beneficiaries: List<Beneficiary>,
)