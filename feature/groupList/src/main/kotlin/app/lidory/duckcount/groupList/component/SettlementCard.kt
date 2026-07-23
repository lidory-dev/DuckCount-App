package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun SettlementCard(
    modifier: Modifier = Modifier,
    debtorFullName: String,
    amount: String,
    onValidateSettlement: () -> Unit,
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
            Text(
                text = stringResource(R.string.owes_you, debtorFullName, amount),
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onValidateSettlement,
                modifier = Modifier.padding(horizontal = Padding.medium),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(androidUiR.string.validate),
                )
            }
        }
    }
}
