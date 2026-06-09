package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

@Composable
fun GroupMemberDetailHeader(
    groupSize: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Text(text = stringResource(R.string.group_size, groupSize))
    }
}