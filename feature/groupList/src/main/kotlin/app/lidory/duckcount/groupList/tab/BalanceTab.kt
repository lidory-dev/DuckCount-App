package app.lidory.duckcount.groupList.tab

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

class BalanceTab : Tab() {
    @Composable
    override fun icon() = painterResource(R.drawable.ic_balance)

    @Composable
    override fun title() = stringResource(R.string.balance)
}
