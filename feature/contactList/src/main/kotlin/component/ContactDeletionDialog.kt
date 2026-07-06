package component

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

@Composable
fun ContactDeletionDialog(
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
                Text(text = stringResource(androidUiR.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(text = stringResource(androidUiR.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.delete_contact)) },
        text = { Text(text = stringResource(R.string.delete_contact_confirmation)) },
    )
}
