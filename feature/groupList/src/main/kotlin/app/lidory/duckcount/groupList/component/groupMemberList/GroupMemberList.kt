package app.lidory.duckcount.groupList.component.groupMemberList

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName

fun LazyListScope.groupMemberList(
    members: List<GroupMemberWithFullName>,
    onModifyMemberList: () -> Unit,
    removeMember: (GroupMember) -> Unit,
    authenticatedUserUuid: String?,
) {
    item {
        GroupMemberHeader(
            isMemberListEmpty = members.isEmpty(),
            onModifyMemberList = onModifyMemberList,
        )
    }

    members.find { it.member.userUuid == authenticatedUserUuid }?.let { memberWithFullName ->
        item(key = memberWithFullName.member.userUuid) {
            GroupMemberRow(
                modifier = Modifier.fillMaxWidth(),
                member = memberWithFullName.member,
                memberFullName = memberWithFullName.fullName,
                removeMember = {},
                isRemovable = false,
            )
        }
    }

    items(
        items = members.filterNot { it.member.userUuid == authenticatedUserUuid },
        key = { memberWithFullName -> memberWithFullName.member.userUuid },
    ) { memberWithFullName ->
        GroupMemberRow(
            modifier = Modifier
                .animateItem(
                    fadeInSpec = tween(200),
                    placementSpec = tween(200),
                )
                .fillMaxWidth(),
            member = memberWithFullName.member,
            memberFullName = memberWithFullName.fullName,
            removeMember = removeMember,
        )
    }
}
