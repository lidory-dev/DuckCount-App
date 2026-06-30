package app.lidory.duckcount.expenseList.model

data class PreviousExpenseUi(
    val modifiedAt: String,
    val modifiedUserFullName: String,
    val previousAmount: String,
    val previousBeneficiaries: List<PreviousBeneficiary>,
    val previousCreditorFullName: String,
    val previousDescription: String,
    val previousIsRefund: Boolean,
    val previousTimestamp: String,
    val previousTitle: String,
)
