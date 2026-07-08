package database.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class ExpenseWithBeneficiariesEntity(
    @Embedded val expense: ExpenseEntity,

    @Relation(
        parentColumn = "uuid",
        entityColumn = "expenseUuid",
    )
    val beneficiaries: List<BeneficiaryEntity>,
)