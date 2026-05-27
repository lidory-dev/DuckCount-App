package app.lidory.duckcount.expenseList.component

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as SharedR

@Composable
fun AddExpenseFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        modifier = modifier,
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(SharedR.drawable.ic_add),
            contentDescription = stringResource(R.string.add_expense),
        )
    }
}
