package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import model.ContactRequest
import viewmodel.ContactRequestAnswer

/**
 * One contact request: who sent it, and the two ways to answer it.
 *
 * Both buttons stay disabled while either answer is being sent, the request can only
 * be answered once.
 */
@Composable
fun ContactRequestCard(
    request: ContactRequest,
    answer: ContactRequestAnswer?,
    onAccept: () -> Unit,
    onRefuse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val senderName = request.senderFullName ?: stringResource(R.string.unresolved_user)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Padding.large, start = Padding.large, end = Padding.large),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TextFieldDefaults.MinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfilePictureCard(
                    modifier = Modifier.fillMaxHeight(),
                    uuid = request.senderUserUuid,
                    fullName = senderName,
                )

                Text(text = senderName)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Padding.medium),
            ) {
                DuckCountOutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onRefuse,
                    enabled = answer == null,
                ) {
                    if (answer == ContactRequestAnswer.REFUSE) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(text = stringResource(R.string.refuse_contact_request))
                    }
                }

                DuckCountLoadingButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                    enabled = answer == null,
                    isLoading = answer == ContactRequestAnswer.ACCEPT,
                ) {
                    Text(text = stringResource(R.string.accept_contact_request))
                }
            }
        }
    }
}
