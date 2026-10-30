package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R

/**
 * Tells the user that [count] expenses of the group, which its members wrote, cannot be read on
 * this device yet, so that the list, the balances and what is left to settle up, which leave them
 * out, are not taken for the whole of it.
 */
@Composable
fun UnreadableExpensesNotice(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            modifier = Modifier.padding(Padding.large),
            text = pluralStringResource(R.plurals.unreadable_expenses_notice, count, count),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
