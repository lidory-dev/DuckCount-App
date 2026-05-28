package app.lidory.duckcount.navigation.key

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.R
import kotlinx.serialization.Serializable
import app.lidory.duckcount.android.ui.R as androidUiR

@Serializable
data object ExpenseListScreenKey : NavigableTabNavKey() {
    @Composable
    override fun title() = stringResource(R.string.expenses)

    @Composable
    override fun icon() = painterResource(androidUiR.drawable.ic_receipt_long)
}
