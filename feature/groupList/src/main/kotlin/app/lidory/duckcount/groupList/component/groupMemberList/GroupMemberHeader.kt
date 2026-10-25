package app.lidory.duckcount.groupList.component.groupMemberList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun GroupMemberHeader(
    numberOfMembers: Int,
    maxMembers: Int,
    enabled: Boolean,
    onModifyMemberList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Takes what the button leaves, wrapping rather than squeezing it, as a longer language or
        // a larger font would.
        Text(
            modifier = Modifier.weight(1f),
            text = pluralStringResource(
                R.plurals.members_out_of_limit,
                numberOfMembers,
                numberOfMembers,
                maxMembers,
            ),
        )

        TextButton(
            enabled = enabled,
            onClick = {
                onModifyMemberList()
                focusManager.clearFocus()
            },
        ) {
            val iconSize = with(LocalDensity.current) {
                (LocalTextStyle.current.fontSize * 1.2f).toDp()
            }

            // The user alone, who is in every group of theirs: nobody added yet.
            val isUserAlone = numberOfMembers == 1

            val iconRes =
                if (isUserAlone) androidUiR.drawable.ic_add else androidUiR.drawable.ic_edit
            val labelRes =
                if (isUserAlone) androidUiR.string.add else androidUiR.string.modify

            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(labelRes),
                modifier = Modifier.size(iconSize),
            )

            Spacer(Modifier.width(4.dp))

            Text(stringResource(labelRes))
        }
    }
}
