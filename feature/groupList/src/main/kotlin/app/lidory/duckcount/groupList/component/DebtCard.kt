package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R

@Composable
fun DebtCard(
    modifier: Modifier = Modifier,
    creditorFullName: String,
    amount: String,
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = Padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.debt_owned, creditorFullName, amount))
        }
    }
}