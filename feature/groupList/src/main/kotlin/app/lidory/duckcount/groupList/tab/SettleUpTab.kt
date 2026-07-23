package app.lidory.duckcount.groupList.tab

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

class SettleUpTab : Tab() {
    @Composable
    override fun icon() = painterResource(R.drawable.ic_checkbook)

    @Composable
    override fun title() = stringResource(R.string.settle_up)
}
