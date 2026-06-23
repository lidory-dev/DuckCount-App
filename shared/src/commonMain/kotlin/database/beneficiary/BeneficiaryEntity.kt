package database.beneficiary

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class BeneficiaryEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val expenseUuid: String,
    val userUuid: String,
    val share: String,
    val isShareFixed: Boolean,
)
