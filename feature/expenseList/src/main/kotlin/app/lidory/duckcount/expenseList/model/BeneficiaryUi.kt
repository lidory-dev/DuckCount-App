package app.lidory.duckcount.expenseList.model

data class BeneficiaryUi(
    val uuid: String,
    val userUuid: String,
    val share: String,

    /** Null when this device holds no profile for them; the screen supplies the wording. */
    val fullName: String?,

    val isShareFixed: Boolean,
)
