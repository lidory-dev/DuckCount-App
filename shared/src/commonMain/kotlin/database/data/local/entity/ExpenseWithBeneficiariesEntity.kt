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

    /** Read with the expense only: it is written apart, see `ExpenseDao.storeSettledMarks`. */
    @Relation(
        parentColumn = "uuid",
        entityColumn = "expenseUuid",
    )
    val settledMark: OneTimeExpenseSettledMarkEntity? = null,
)
