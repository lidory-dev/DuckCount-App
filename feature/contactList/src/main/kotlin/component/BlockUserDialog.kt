package component

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Asks the user to confirm they are blocking somebody, [title] and [text] saying what it does to
 * a contact or to the sender of a request.
 */
@Composable
fun BlockUserDialog(
    @StringRes title: Int,
    @StringRes text: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.buttonColors().copy(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                onClick = onConfirm,
            ) {
                Text(text = stringResource(R.string.block_contact))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(text = stringResource(androidUiR.string.cancel))
            }
        },
        title = { Text(text = stringResource(title)) },
        text = { Text(text = stringResource(text)) },
    )
}
