package app.lidory.duckcount.groupList.model

data class ExpenseWithBeneficiaries(
    val uuid: String,
    val timestamp: String,
    val title: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isSettlement: Boolean,
    val currency: Currency,
    val beneficiaries: List<Beneficiary>,
    val groupUuid: String?,
    val isDeleted: Boolean,
)