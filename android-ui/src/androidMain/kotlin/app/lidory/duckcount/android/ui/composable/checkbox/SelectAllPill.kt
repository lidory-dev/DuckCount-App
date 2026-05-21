package app.lidory.duckcount.android.ui.composable.checkbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun SelectAllPill(
    toggleSelectAllUsers: () -> Unit,
    toggleableListState: ToggleableState,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(Padding.extraLarge),
        modifier = modifier
            .clip(RoundedCornerShape(Padding.extraLarge))
            .clickable(
                onClick = {
                    toggleSelectAllUsers()
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                modifier = Modifier.padding(start = Padding.large),
                text = stringResource(R.string.select_all),
            )

            DuckCountTriStateCheckbox(
                state = toggleableListState,
                onClick = {
                    toggleSelectAllUsers()
                },
            )
        }
    }
}
