package app.lidory.duckcount.navigation.key

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.R
import kotlinx.serialization.Serializable

@Serializable
data object ContactListScreenKey : NavigableTabNavKey() {
    @Composable
    override fun title() = stringResource(R.string.contacts)

    @Composable
    override fun icon() = painterResource(R.drawable.ic_contacts)
}
