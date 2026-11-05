package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * Tells the user how many people they blocked and takes them to the screen that lists them, where
 * they can unblock them. Whether it shows at all is the caller's business, it has nothing to say at
 * zero.
 */
@Composable
fun BlockedUsersCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight)
                .padding(horizontal = Padding.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_block),
                contentDescription = null,
            )

            Text(
                modifier = Modifier.weight(1f),
                text = pluralStringResource(R.plurals.blocked_users_count, count, count),
            )

            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
            )
        }
    }
}
