package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * A debt the user cannot record paid back, worded as [text]: what they owe another member, or what
 * one other member owes another. Only its creditor settles a debt, see [SettlementCard].
 */
@Composable
fun DebtCard(
    modifier: Modifier = Modifier,
    text: String,
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
            Text(text = text)
        }
    }
}
