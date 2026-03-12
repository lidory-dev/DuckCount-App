package database.expense

import androidx.room.Embedded
import androidx.room.Relation
import database.beneficiary.BeneficiaryEntity

data class ExpenseWithBeneficiariesEntity(
    @Embedded val expense: ExpenseEntity,

    @Relation(
        parentColumn = "uuid",
        entityColumn = "expenseUuid"
    )
    val beneficiaries: List<BeneficiaryEntity>,
)
