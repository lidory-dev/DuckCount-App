package app.lidory.duckcount.expenseList.model

import androidx.annotation.StringRes
import app.lidory.duckcount.expenseList.R
import kotlinx.datetime.LocalDateTime
import app.lidory.duckcount.android.ui.R as androidUiR

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

/** What a version did to the expense, which its history tells with who did it, see [changeFrom]. */
enum class VersionChange {
    CREATION,
    EDIT,

    /** The expense of a group deleted, which stays listed struck through, see [ExpenseWithBeneficiaries.isDeleted]. */
    DELETION,

    /** The expense of a group brought back from its deletion. */
    RESTORATION,
}

/**
 * What this version did to the expense, [earlier] being the version before it in its history, or
 * null when the history keeps none: a version saying the expense is deleted deleted it, and one
 * saying it is not, coming after one that did, restored it. Without the version before it, a
 * restoration is told as an edit, which it is too.
 */
fun ExpenseVersion.changeFrom(earlier: ExpenseVersion?): VersionChange = when {
    isCreation -> VersionChange.CREATION
    expense.isDeleted -> VersionChange.DELETION
    earlier?.expense?.isDeleted == true -> VersionChange.RESTORATION
    else -> VersionChange.EDIT
}

/** The line naming who made a version that did [this], their name its one argument. */
@StringRes
fun VersionChange.authorLineRes(): Int = when (this) {
    VersionChange.CREATION -> R.string.created_by
    VersionChange.EDIT -> androidUiR.string.modified_by
    VersionChange.DELETION -> R.string.deleted_by
    VersionChange.RESTORATION -> R.string.restored_by
}
