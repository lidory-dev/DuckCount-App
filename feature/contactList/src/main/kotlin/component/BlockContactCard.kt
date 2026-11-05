package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * Offers to block the contact, in the error color, as something that cannot be taken back without
 * a new request: blocking removes them. Shows progress in place of the icon while [isBlocking].
 */
@Composable
fun BlockContactCard(
    isBlocking: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight)
                .padding(horizontal = Padding.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            if (isBlocking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_block),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.block_contact),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
