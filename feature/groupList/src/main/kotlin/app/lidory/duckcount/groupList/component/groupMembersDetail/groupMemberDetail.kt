package app.lidory.duckcount.groupList.component.groupMembersDetail

import androidx.compose.animation.core.tween
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
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName

fun LazyListScope.groupMemberDetail(
    members: List<GroupMemberWithFullName>,
    authenticatedUserUuid: String?,
    openBottomSheet: (String) -> Unit,
) {
    item {
        GroupMemberDetailHeader(
            groupSize = members.size,
        )
    }

    members.find { it.member.userUuid == authenticatedUserUuid }?.let { member ->
        item(key = member.member.uuid) {
            MemberDetailCard(
                member = member,
                onClick = {
                    openBottomSheet(member.member.userUuid)
                },
            )
        }
    }

    items(
        items = members.filterNot { it.member.userUuid == authenticatedUserUuid },
        key = { member -> member.member.userUuid },
    ) { member ->
        MemberDetailCard(
            member = member,
            onClick = {
                openBottomSheet(member.member.userUuid)
            },
        )
    }
}

@Composable
fun LazyItemScope.MemberDetailCard(
    member: GroupMemberWithFullName,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(size = Padding.large),
        modifier = Modifier
            .fillMaxWidth()
            .animateItem(
                fadeInSpec = tween(200),
                placementSpec = tween(200),
            )
            .padding(vertical = Padding.medium),
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
        ) {
            ProfilePictureCard(
                modifier = Modifier.fillMaxHeight(),
                // TODO: use profile picture
                profilePicture = null,
                uuid = member.member.userUuid,
                fullName = member.fullName,
            )

            Text(
                text = member.fullName,
            )
        }
    }
}
