package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMemberBottomSheet(
    userUuid: String,
    removeUserFromGroup: (String) -> Unit,
    bottomSheetState: SheetState,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = bottomSheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(
                onClick = {
                    removeUserFromGroup(userUuid)
                },
            ) {
                Text(text = stringResource(R.string.remove_from_the_group))
            }
        }
    }
}
