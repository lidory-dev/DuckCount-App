package app.lidory.duckcount.expenseList.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as androidUiR

data class PreviousExpenseUi(
    val modifiedAt: String,
    val modifierUserFullName: String,
    val previousAmount: String,
    val previousBeneficiaries: List<PreviousBeneficiary>,
    val previousCreditorFullName: String,
    val previousDescription: String,
    val previousIsSettlement: Boolean,
    val previousTimestamp: String,
    val previousTitle: String,
)

@Composable
fun PreviousExpenseUi.compareToCurrent(currentExpenseUi: PreviousExpenseUi): List<String> {
    val differences = mutableListOf<String>()

    if (previousTitle != currentExpenseUi.previousTitle) {
        differences.add(stringResource(androidUiR.string.title))
    }

    if (previousDescription != currentExpenseUi.previousDescription) {
        differences.add(stringResource(androidUiR.string.description))
    }

    if (previousCreditorFullName != currentExpenseUi.previousCreditorFullName) {
        differences.add(stringResource(R.string.creditor))
    }

    if (previousAmount != currentExpenseUi.previousAmount) {
        differences.add(stringResource(R.string.amount_expense))
    }

    if (previousBeneficiaries != currentExpenseUi.previousBeneficiaries) {
        differences.add(stringResource(R.string.beneficiaries))
    }

    if (previousTimestamp != currentExpenseUi.previousTimestamp) {
        differences.add(stringResource(R.string.time_of_expense))
    }

    if (previousIsSettlement != currentExpenseUi.previousIsSettlement) {
        differences.add(stringResource(R.string.settlement))
    }

    return differences
}
