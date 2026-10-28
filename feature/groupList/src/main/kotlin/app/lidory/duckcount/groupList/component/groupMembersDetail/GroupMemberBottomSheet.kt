package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.model.messageRes
import app.lidory.duckcount.groupList.viewmodel.MemberSheetAction
import app.lidory.duckcount.groupList.viewmodel.MemberSheetState

/**
 * A member of the group other than the user, see [MemberSheetState]: who they are, and what
 * [sheet] offers to do about them, see [MemberSheetAction], each its own full-width button. What
 * their relation to the user calls for comes first, filled, and their removal last, outlined, in
 * the error colour, as the removal dialog's button is, see [GroupMemberRemovalDialog].
 *
 * While a contact request to them is on its way to the server, its button shows an in-place
 * spinner, every other one is disabled, and the sheet cannot be dismissed, by swiping it down,
 * tapping the scrim or going back, until it is answered: why it was not sent then shows under the
 * button, for the user to try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMemberBottomSheet(
    sheet: MemberSheetState,
    onViewProfile: () -> Unit,
    onAnswerRequest: () -> Unit,
    onSendRequest: () -> Unit,
    onRemove: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSendingRequest by rememberUpdatedState(sheet.isSendingRequest)

    // Its own state for each sheet opened, which leaves with it. Skipping the half-open state,
    // so that the buttons are in view at once, in landscape say. Hiding it is refused while a
    // request is on its way, by swiping it down or tapping the scrim; going back is taken before
    // the sheet sees it, see the BackHandler below. The view model refuses onDismissRequest
    // meanwhile all the same, see GroupSettingsViewModel.closeMember.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !isSendingRequest },
    )

    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        // Going back does nothing while a request is on its way, as on the screens that wait on
        // the server. Left to the sheet, a back gesture would shrink it as it went, and leave it
        // shrunk once the hide it asks for is refused. In here, so that it is added to the
        // sheet's own window, the one going back is told to, and after the sheet's handler,
        // which makes it the one asked first.
        BackHandler(enabled = sheet.isSendingRequest) {}

        // The sheet pads its content clear of the navigation bar. Scrolls when it does not fit,
        // in landscape or with a large font for instance, which also brings the error into view.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Padding.extraLarge)
                .padding(bottom = Padding.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            val member = sheet.member
            val displayName = member.fullName ?: stringResource(R.string.unknown_member)

            ProfilePictureCard(
                modifier = Modifier.size(96.dp),
                // TODO: use profile picture
                profilePicture = null,
                uuid = member.member.userUuid,
                fullName = displayName,
            )

            Text(
                modifier = Modifier.padding(bottom = Padding.large),
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            val isEnabled = !sheet.isSendingRequest

            sheet.actions.forEach { action ->
                when (action) {
                    MemberSheetAction.VIEW_PROFILE -> DuckCountButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onViewProfile,
                        enabled = isEnabled,
                    ) {
                        Text(text = stringResource(R.string.member_view_profile))
                    }

                    MemberSheetAction.ANSWER_REQUEST -> DuckCountButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAnswerRequest,
                        enabled = isEnabled,
                    ) {
                        Text(text = stringResource(R.string.member_answer_contact_request))
                    }

                    // Disabled for good: what it tells is that the request waits for their answer.
                    MemberSheetAction.REQUEST_SENT -> DuckCountButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
                        enabled = false,
                    ) {
                        Text(text = stringResource(R.string.member_contact_request_sent))
                    }

                    MemberSheetAction.SEND_REQUEST -> DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSendRequest,
                        enabled = isEnabled,
                        isLoading = sheet.isSendingRequest,
                    ) {
                        Text(text = stringResource(R.string.member_send_contact_request))
                    }

                    MemberSheetAction.REMOVE -> DuckCountOutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRemove,
                        enabled = isEnabled,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = isEnabled),
                    ) {
                        Text(text = stringResource(R.string.remove_from_the_group))
                    }
                }

                // Under the button the request was sent from: their relation's, which the removal
                // never is, and which may tell where the user stands with them since, the server
                // having said they are a contact already, say.
                if (action != MemberSheetAction.REMOVE) {
                    sheet.requestError?.let { error ->
                        DuckCountErrorText(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Padding.medium),
                            text = stringResource(error.messageRes()),
                        )
                    }
                }
            }
        }
    }
}
