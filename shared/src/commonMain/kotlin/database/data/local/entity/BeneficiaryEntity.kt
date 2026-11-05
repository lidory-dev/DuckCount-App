package database.data.local.entity

import androidx.room.Entity

@Entity(primaryKeys = ["expenseUuid", "userUuid"])
data class BeneficiaryEntity(
    val expenseUuid: String,
    val userUuid: String,
    val share: String,
    val isShareFixed: Boolean,
    val sharePercentage: String? = null,
)
