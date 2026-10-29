package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.component.OwnerLabel
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.viewmodel.MemberActions

/**
 * The group's [members], the user first, under how many there are, see [GroupMemberDetailHeader].
 * Any member but the user opens their sheet, [openBottomSheet], when tapped, see
 * [GroupMemberBottomSheet]; the user is shown alone.
 */
fun LazyListScope.groupMemberDetail(
    members: List<GroupMemberWithFullName>,
    authenticatedUserUuid: String?,
    actions: MemberActions,
    onAddMembers: () -> Unit,
    openBottomSheet: (GroupMemberWithFullName) -> Unit,
) {
    item {
        GroupMemberDetailHeader(
            groupSize = members.size,
            actions = actions,
            onAddMembers = onAddMembers,
        )
    }

    members.find { it.member.userUuid == authenticatedUserUuid }?.let { member ->
        item(key = member.member.userUuid) {
            MemberDetailCard(
                member = member,
                onClick = null,
            )
        }
    }

    items(
        items = members.filterNot { it.member.userUuid == authenticatedUserUuid },
        key = { member -> member.member.userUuid },
    ) { member ->
        MemberDetailCard(
            member = member,
            onClick = { openBottomSheet(member) },
        )
    }
}

/**
 * [member], tappable when [onClick] is not null, marked when they own the group, see [OwnerLabel].
 */
@Composable
fun LazyItemScope.MemberDetailCard(
    member: GroupMemberWithFullName,
    onClick: (() -> Unit)?,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .animateItem(
            fadeInSpec = tween(200),
            placementSpec = tween(200),
        )
        .padding(vertical = Padding.medium)
    val shape = RoundedCornerShape(size = Padding.large)

    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
        ) {
            val displayName = member.fullName ?: stringResource(R.string.unknown_member)

            ProfilePictureCard(
                modifier = Modifier.fillMaxHeight(),
                // TODO: use profile picture
                profilePicture = null,
                uuid = member.member.userUuid,
                fullName = displayName,
            )

            // On one line, cut short, as the row's height holds no more, the owner's label taking
            // some of its width.
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                text = displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (member.member.isAdmin) OwnerLabel()
        }
    }

    if (onClick != null) {
        Card(shape = shape, modifier = modifier, onClick = onClick, content = content)
    } else {
        Card(shape = shape, modifier = modifier, content = content)
    }
}
