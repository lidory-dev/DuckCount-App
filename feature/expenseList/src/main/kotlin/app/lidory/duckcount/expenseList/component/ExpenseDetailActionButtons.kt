package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun ExpenseDetailActionButtons(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDeleted: Boolean,
    isSettlement: Boolean,
    isOneTimeExpense: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canRestore: Boolean,
    restore: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
    history: () -> Unit,
) {
    val openAlertDialog = remember { mutableStateOf(false) }

    when {
        openAlertDialog.value -> {
            AlertDialog(
                onDismissRequest = { openAlertDialog.value = false },
                confirmButton = {
                    TextButton(
                        colors = ButtonDefaults.buttonColors().copy(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            openAlertDialog.value = false
                            delete()
                        },
                    ) {
                        Text(text = stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { openAlertDialog.value = false },
                    ) {
                        Text(text = stringResource(androidUiR.string.cancel))
                    }
                },
                title = {
                    Text(text = stringResource(R.string.delete))
                },
                text = {
                    Column {
                        val confirmationText = if (isSettlement) {
                            R.string.delete_settlement_confirmation
                        } else {
                            R.string.delete_one_time_expense_confirmation
                        }

                        Text(text = stringResource(confirmationText))
                    }
                },
            )
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDeleted) {
            if (canRestore) {
                IconButton(
                    enabled = enabled,
                    onClick = restore,
                ) {
                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_restore_from_trash),
                        contentDescription = stringResource(R.string.restore),
                    )
                }
            }
        } else {
            if (canEdit) {
                IconButton(
                    enabled = enabled,
                    onClick = edit,
                ) {
                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                    )
                }
            }

            if (!isSettlement) {
                IconButton(
                    enabled = enabled,
                    onClick = history,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = stringResource(R.string.history),
                    )
                }
            }

            if (canDelete) {
                IconButton(
                    enabled = enabled,
                    onClick = {
                        if (isSettlement || isOneTimeExpense) {
                            openAlertDialog.value = !openAlertDialog.value
                        } else {
                            delete()
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete),
                    )
                }
            }
        }
    }
}
