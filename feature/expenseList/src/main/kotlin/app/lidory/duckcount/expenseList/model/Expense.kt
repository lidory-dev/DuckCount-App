package app.lidory.duckcount.expenseList.model

data class Expense(
    val uuid: String,
    val timestamp: String,
    val title: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isRefund: Boolean,
    val currency: Currency,
    val groupUuid: String? = null,
)
