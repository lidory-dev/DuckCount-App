package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.viewmodel.MemberActions
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * How many members the group has, with the button that adds some for the user who can, see
 * [MemberActions]: disabled while a change waits on the server, with a word as to why.
 */
@Composable
fun GroupMemberDetailHeader(
    groupSize: Int,
    actions: MemberActions,
    onAddMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Takes what the button leaves, wrapping rather than squeezing it, as on the group form.
            Text(
                modifier = Modifier.weight(1f),
                text = pluralStringResource(R.plurals.group_size, groupSize, groupSize),
            )

            if (actions != MemberActions.NONE) {
                TextButton(
                    enabled = actions == MemberActions.AVAILABLE,
                    onClick = onAddMembers,
                ) {
                    val iconSize = with(LocalDensity.current) {
                        (LocalTextStyle.current.fontSize * 1.2f).toDp()
                    }

                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                    )

                    Spacer(Modifier.width(4.dp))

                    Text(stringResource(R.string.add_members))
                }
            }
        }

        if (actions == MemberActions.WAITING) {
            Text(
                text = stringResource(R.string.group_members_change_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
