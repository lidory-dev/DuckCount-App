package app.lidory.duckcount.groupList.model

data class TransactionWithFullName(
    val debtorUuid: String,
    val debtorFullName: String,
    val creditorUuid: String,
    val creditorFullName: String,
    val amount: String,
)