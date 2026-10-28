package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Asks the user to confirm leaving the group [groupTitle], saying what becomes of it, and shows
 * the leave under way, [isLeaving], until it is answered, with both buttons disabled meanwhile, as
 * for a member's removal, see
 * [GroupMemberRemovalDialog][app.lidory.duckcount.groupList.component.groupMembersDetail.GroupMemberRemovalDialog].
 */
@Composable
fun GroupLeaveDialog(
    groupTitle: String,
    isLeaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !isLeaving,
                // Text button colours rather than transparent button ones: those turn grey once
                // disabled.
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = onConfirm,
            ) {
                if (isLeaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(text = stringResource(androidUiR.string.leave))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isLeaving,
                onClick = onDismiss,
            ) {
                Text(text = stringResource(androidUiR.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.leave_the_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Padding.large)) {
                Text(text = stringResource(R.string.leave_group_confirmation, groupTitle))
                Text(text = stringResource(R.string.leave_group_explanation))
            }
        },
    )
}
