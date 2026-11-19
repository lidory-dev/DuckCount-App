package app.lidory.duckcount.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.R

/**
 * Who the user is, at the top of the settings: the avatar their contacts see, and their name,
 * leading to their profile.
 */
@Composable
fun ProfileCard(
    userUuid: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
        onClick = onClick,
    ) {
        // The avatar keeps a margin of its own inside its size: 16dp of room around it in all.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Padding.medium, end = Padding.large, top = Padding.medium, bottom = Padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilePictureCard(
                modifier = Modifier.size(88.dp),
                uuid = userUuid,
                fullName = name,
            )

            Spacer(modifier = Modifier.width(Padding.medium))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(R.string.profile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
