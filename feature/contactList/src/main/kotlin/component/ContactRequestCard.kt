package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import model.ContactRequest
import viewmodel.ContactRequestAnswer

/**
 * One contact request: who sent it, the two ways to answer it, and blocking its sender, which
 * refuses it too.
 *
 * Every button stays disabled while any answer is being sent, the request can only be answered
 * once. Accept also stays disabled until the sender's name could be read: without it the card
 * shows nothing about who would be handed the user's profile. Blocking does not wait for it:
 * nothing is handed over.
 */
@Composable
fun ContactRequestCard(
    request: ContactRequest,
    answer: ContactRequestAnswer?,
    onAccept: () -> Unit,
    onRefuse: () -> Unit,
    onBlock: () -> Unit,
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
                    onClick = onBlock,
                    enabled = answer == null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = ThreeButtonPadding,
                ) {
                    if (answer == ContactRequestAnswer.BLOCK) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        ButtonLabel(text = stringResource(R.string.block_contact_request_sender))
                    }
                }

                DuckCountOutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onRefuse,
                    enabled = answer == null,
                    contentPadding = ThreeButtonPadding,
                ) {
                    if (answer == ContactRequestAnswer.REFUSE) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        ButtonLabel(text = stringResource(R.string.refuse_contact_request))
                    }
                }

                DuckCountLoadingButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                    enabled = answer == null && request.senderFullName != null,
                    isLoading = answer == ContactRequestAnswer.ACCEPT,
                    contentPadding = ThreeButtonPadding,
                ) {
                    ButtonLabel(text = stringResource(R.string.accept_contact_request))
                }
            }
        }
    }
}

/**
 * Narrower than a button's own padding on the sides: three buttons share the card's width, and the
 * default would leave a longer label, French ones say, a few letters short of fitting.
 */
private val ThreeButtonPadding = PaddingValues(horizontal = Padding.medium, vertical = 8.dp)

/** A label on one line, cut short rather than wrapped, should a large font size still not fit it. */
@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
