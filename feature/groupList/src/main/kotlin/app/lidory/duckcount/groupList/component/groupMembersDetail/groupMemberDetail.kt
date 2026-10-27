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
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.viewmodel.MemberActions
import app.lidory.duckcount.groupList.viewmodel.isRemovable

/**
 * The group's [members], the user first, under how many there are, see [GroupMemberDetailHeader].
 * A member the user can remove, see [isRemovable], opens [openBottomSheet] when tapped; any other
 * is shown alone.
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

    fun onClickOf(member: GroupMemberWithFullName): (() -> Unit)? =
        if (isRemovable(userUuid = member.member.userUuid, selfUuid = authenticatedUserUuid, actions = actions)) {
            { openBottomSheet(member) }
        } else {
            null
        }

    members.find { it.member.userUuid == authenticatedUserUuid }?.let { member ->
        item(key = member.member.userUuid) {
            MemberDetailCard(
                member = member,
                onClick = onClickOf(member),
            )
        }
    }

    items(
        items = members.filterNot { it.member.userUuid == authenticatedUserUuid },
        key = { member -> member.member.userUuid },
    ) { member ->
        MemberDetailCard(
            member = member,
            onClick = onClickOf(member),
        )
    }
}

/** [member], tappable when [onClick] is not null. */
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

            Text(
                text = displayName,
            )
        }
    }

    if (onClick != null) {
        Card(shape = shape, modifier = modifier, onClick = onClick, content = content)
    } else {
        Card(shape = shape, modifier = modifier, content = content)
    }
}
