package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R

/**
 * Tells the user that they are no longer a member of the group, which stays on this device,
 * read-only, in neutral words that blame nobody.
 */
@Composable
fun LeftGroupNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            modifier = Modifier.padding(Padding.large),
            text = stringResource(R.string.left_group_notice),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
