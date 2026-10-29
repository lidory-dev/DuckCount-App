package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.groupList.R

/**
 * Marks the group's owner on the right of their card: its creator, the member whose
 * [app.lidory.duckcount.groupList.model.GroupMember.isAdmin] is set, the one who changes its
 * members and edits it. As far from the card's edge as a name is in the group form's rows.
 */
@Composable
fun OwnerLabel(modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.padding(end = 16.dp),
        text = stringResource(R.string.group_owner),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
