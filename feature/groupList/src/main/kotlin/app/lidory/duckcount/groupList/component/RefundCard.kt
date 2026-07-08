package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun RefundCard(
    modifier: Modifier = Modifier,
    debtorFullName: String,
    amount: String,
    onValidateRefund: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Padding.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.refund_owned, debtorFullName, amount))

            var dropDownMenuExpanded by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { dropDownMenuExpanded = !dropDownMenuExpanded }) {
                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_more_vertical),
                        contentDescription = stringResource(R.string.more_options),
                    )
                }

                DropdownMenu(
                    expanded = dropDownMenuExpanded,
                    onDismissRequest = { dropDownMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.confirm_refund)) },
                        onClick = {
                            dropDownMenuExpanded = !dropDownMenuExpanded
                            onValidateRefund()
                        },
                    )
                }
            }
        }
    }
}