package app.lidory.duckcount.expenseList.model

import kotlinx.datetime.LocalDateTime

/** One version of an expense, as its history shows it. */
data class ExpenseVersion(
    /** Which version this is, which no other version of the expense shares. */
    val revision: Long,

    val expense: ExpenseWithBeneficiaries,

    /** Who made this version. */
    val authorUuid: String,

    /** Whether this is the expense as it was created, rather than as an edit left it. */
    val isCreation: Boolean,

    /**
     * When this version was made, or null when that is not known: for the version created, the
     * expense's own date as written, like everywhere it is shown; for an edit, in the device's
     * time zone.
     */
    val madeAt: LocalDateTime?,
)
