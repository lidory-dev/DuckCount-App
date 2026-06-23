package app.lidory.duckcount.expenseList.model

data class BeneficiaryUi(
    val uuid: String,
    val userUuid: String,
    val share: String,
    val fullName: String,
    val isShareFixed: Boolean,
)
