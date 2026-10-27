package app.lidory.duckcount.groupList.component.groupMembersDetail

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
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Asks the user to confirm removing [memberName] from the group [groupTitle], and shows the
 * removal under way, [isRemoving], until it is answered, with both buttons disabled meanwhile.
 */
@Composable
fun GroupMemberRemovalDialog(
    memberName: String,
    groupTitle: String,
    isRemoving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !isRemoving,
                // Text button colours rather than transparent button ones: those turn grey once
                // disabled.
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = onConfirm,
            ) {
                if (isRemoving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(text = stringResource(androidUiR.string.remove))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isRemoving,
                onClick = onDismiss,
            ) {
                Text(text = stringResource(androidUiR.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.remove_from_the_group)) },
        text = { Text(text = stringResource(R.string.remove_member_confirmation, memberName, groupTitle)) },
    )
}
