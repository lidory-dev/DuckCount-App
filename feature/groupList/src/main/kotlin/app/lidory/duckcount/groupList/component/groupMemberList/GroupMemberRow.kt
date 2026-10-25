package app.lidory.duckcount.groupList.component.groupMemberList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.R as groupListR

@Composable
fun GroupMemberRow(
    member: GroupMember,
    memberFullName: String?,
    removeMember: (GroupMember) -> Unit,
    modifier: Modifier = Modifier,
    isRemovable: Boolean = true,
    enabled: Boolean = true,
) {
    Card(
        shape = RoundedCornerShape(size = Padding.large),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
        ) {
            Text(
                text = memberFullName ?: stringResource(groupListR.string.unknown_member),
                modifier = Modifier
                    .padding(TextFieldDefaults.contentPaddingWithLabel()),
            )

            if (isRemovable) {
                IconButton(
                    enabled = enabled,
                    onClick = { removeMember(member) },
                ) {
                    Icon(painter = painterResource(R.drawable.ic_close), stringResource(R.string.remove))
                }
            }
        }
    }
}
