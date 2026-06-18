package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun ExpenseDetailActionDropDownMenu(
    modifier: Modifier = Modifier,
    isDeleted: Boolean,
    restore: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier,
    ) {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                painter = painterResource(androidUiR.drawable.ic_more_vertical),
                contentDescription = stringResource(R.string.more_options),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (isDeleted) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.restore)) },
                    onClick = {
                        restore()
                        expanded = false
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.edit)) },
                    onClick = edit,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.delete)) },
                    onClick = delete,
                )
            }
        }
    }
}