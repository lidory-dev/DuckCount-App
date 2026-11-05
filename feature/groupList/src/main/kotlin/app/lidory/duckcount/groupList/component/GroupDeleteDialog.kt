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
 * Asks the user to confirm deleting the group [groupTitle] for everybody, saying what becomes of it
 * and of its balances, whether settled or not, and shows the deletion under way, [isDeleting], until
 * it is answered, with both buttons disabled meanwhile, as for leaving it, see [GroupLeaveDialog].
 */
@Composable
fun GroupDeleteDialog(
    groupTitle: String,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !isDeleting,
                // Text button colours rather than transparent button ones: those turn grey once
                // disabled.
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = onConfirm,
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(text = stringResource(androidUiR.string.delete))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onDismiss,
            ) {
                Text(text = stringResource(androidUiR.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.delete_the_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Padding.large)) {
                Text(text = stringResource(R.string.delete_group_confirmation, groupTitle))
                Text(text = stringResource(R.string.delete_group_explanation))
            }
        },
    )
}
