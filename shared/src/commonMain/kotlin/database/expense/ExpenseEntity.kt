package database.expense

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.currency.Currency

@Entity
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val title: String,
    val timestamp: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isRefund: Boolean,
    val currency: Currency,
    val groupUuid: String? = null,
    val isDeleted: Boolean = false,
)
