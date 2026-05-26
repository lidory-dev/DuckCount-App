package app.lidory.duckcount.groupList.component.groupMemberList

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.viewmodel.MemberWithFullName

fun LazyListScope.groupMemberList(
    members: List<MemberWithFullName>,
    onModifyMemberList: () -> Unit,
    removeMember: (GroupMember) -> Unit,
) {
    item {
        GroupMemberHeader(
            isMemberListEmpty = members.isEmpty(),
            onModifyMemberList = onModifyMemberList,
        )
    }

    items(
        items = members,
        key = { memberWithFullName -> memberWithFullName.member.uuid },
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
