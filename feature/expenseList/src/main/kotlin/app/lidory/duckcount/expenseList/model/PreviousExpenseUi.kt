package app.lidory.duckcount.expenseList.model

data class PreviousExpenseUi(
    val modifiedAt: String,
    val editorUserUuid: String,
    val previousAmount: String,
    val previousBeneficiaries: List<PreviousBeneficiary>,
    val previousCreditorUuid: String,
    val previousDescription: String,
    val previousIsSettlement: Boolean,
    val previousTimestamp: String,
    val previousTitle: String,
)
