package app.lidory.duckcount.expenseList.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.text.listedNames
import app.lidory.duckcount.expenseList.R

/**
 * Why saving the expense form did not go through, at a granularity the form can explain.
 * The composeApp callback impl maps the shared error onto this.
 */
sealed interface ExpenseSaveError {

    /** For a reason told in a sentence of its own, see [ExpenseWriteError]. */
    data class Write(val error: ExpenseWriteError) : ExpenseSaveError

    /**
     * The server refused the expense for naming people who deleted their account, [beneficiaries],
     * in the order the form lists them: every one a new expense names, and only the ones an edit
     * adds, one the expense names already being kept. The form takes them out of its selection, see
     * [withoutDeletedAccounts][app.lidory.duckcount.expenseList.viewmodel.withoutDeletedAccounts],
     * for the user to save it again: the list beneficiaries are picked from drops a contact known
     * to have deleted their account, who could then not be unticked there.
     */
    data class BeneficiariesDeleted(val beneficiaries: List<DeletedBeneficiary>) : ExpenseSaveError

    /**
     * The expense is one of a group the user is no longer a member of, removed or left, which stays
     * read-only: the form can still be open as a sync reads the removal.
     */
    data object GroupLeft : ExpenseSaveError

    /**
     * Another member deleted the expense as the form was open, which the form shows from then on
     * and saves nothing over: an edit written into a deleted expense would count in no balance, and
     * nobody would be told of it. The detail screen offers to restore it. Deleting it is no edit,
     * so the server takes an edit written after a deletion: what keeps one from being sent is this
     * device showing the deletion, as the form is saved, or once the server refused the edit for
     * another member's.
     */
    data object DeletedMeanwhile : ExpenseSaveError
}

/** Somebody who deleted their account, by the name this device shows for them, [fullName], if any. */
data class DeletedBeneficiary(val userUuid: String, val fullName: String?)

/**
 * The names [ExpenseSaveError.BeneficiariesDeleted] is told with, in its order, or null when this
 * device has none for one of them: the form then says how many deleted their account, rather than
 * leaving somebody out of a list of names.
 */
fun ExpenseSaveError.BeneficiariesDeleted.fullNames(): List<String>? =
    beneficiaries.mapNotNull { it.fullName }.takeIf { it.size == beneficiaries.size }

/** What the form says of [this]. */
@Composable
fun ExpenseSaveError.message(): String = when (this) {
    is ExpenseSaveError.Write -> stringResource(error.messageRes())

    ExpenseSaveError.GroupLeft -> stringResource(R.string.write_error_group_left)

    ExpenseSaveError.DeletedMeanwhile -> stringResource(R.string.write_error_deleted_meanwhile)

    is ExpenseSaveError.BeneficiariesDeleted -> {
        val names = fullNames()

        if (names == null) {
            pluralStringResource(
                R.plurals.write_error_unnamed_beneficiaries_deleted,
                beneficiaries.size,
                beneficiaries.size,
            )
        } else {
            pluralStringResource(
                R.plurals.write_error_beneficiaries_deleted,
                names.size,
                listedNames(names = names),
            )
        }
    }
}
