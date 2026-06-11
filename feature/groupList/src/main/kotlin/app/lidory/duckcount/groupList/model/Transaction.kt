package app.lidory.duckcount.groupList.model

data class Transaction(
    val debtorId: String,
    val creditorId: String,
    val amount: String,
)