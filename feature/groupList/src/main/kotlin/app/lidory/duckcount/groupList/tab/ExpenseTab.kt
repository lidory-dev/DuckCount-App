package app.lidory.duckcount.groupList.tab

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

class ExpenseTab : Tab() {
    @Composable
    override fun icon() = painterResource(androidUiR.drawable.ic_receipt_long)

    @Composable
    override fun title() = stringResource(R.string.expenses)
}
