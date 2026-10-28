package app.lidory.duckcount.groupList.model

/** What [debtorUuid] is to pay back to [creditorUuid] for the group to be settled up, see [TransactionWithFullName]. */
data class Transaction(
    val debtorUuid: String,
    val creditorUuid: String,
    val amount: String,
)
