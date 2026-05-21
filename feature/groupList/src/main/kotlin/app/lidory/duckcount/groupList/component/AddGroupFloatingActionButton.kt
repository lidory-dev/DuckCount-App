package app.lidory.duckcount.groupList.component

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun AddGroupFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        modifier = modifier,
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(androidUiR.drawable.ic_add),
            contentDescription = stringResource(androidUiR.string.add),
        )
    }
}
