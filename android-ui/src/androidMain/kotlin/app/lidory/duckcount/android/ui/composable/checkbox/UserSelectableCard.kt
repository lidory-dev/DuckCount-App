package app.lidory.duckcount.android.ui.composable.checkbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * A person a picker lists, [fullName], picked or not, [isChecked], their picture on the left as
 * the contacts tab shows them, see [ProfilePictureCard], drawn from their [uuid] while they have
 * none.
 */
@Composable
fun UserSelectableCard(
    modifier: Modifier = Modifier,
    toggleSelectedUser: () -> Unit,
    uuid: String,
    fullName: String,
    isChecked: Boolean,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(size = Padding.large),
        onClick = toggleSelectedUser,
        enabled = enabled,
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
        ) {
            ProfilePictureCard(
                modifier = Modifier.fillMaxHeight(),
                // TODO: use profile picture
                profilePicture = null,
                uuid = uuid,
                fullName = fullName,
            )

            Text(
                text = fullName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            DuckCountCheckbox(
                checked = isChecked,
                onCheckedChange = {
                    toggleSelectedUser()
                },
                enabled = enabled,
            )
        }
    }
}
