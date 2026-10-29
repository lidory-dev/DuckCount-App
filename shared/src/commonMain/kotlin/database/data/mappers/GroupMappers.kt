package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupWithMembersEntity
import database.domain.Group
import database.domain.GroupWithMembers

fun GroupEntity.toGroup() = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
    isLeft = isLeft,
)

/**
 * This group as the user [selfUuid] sees it, null with nobody signed in, with [hasPendingChange]
 * and [isCreatorBlocked] read alongside, see [database.data.local.dao.GroupDao.hasPendingChange]
 * and [database.data.local.dao.GroupDao.isCreatorBlockedAsFlow].
 */
fun GroupWithMembersEntity.toGroupWithMembers(
    selfUuid: String?,
    hasPendingChange: Boolean,
    isCreatorBlocked: Boolean,
): GroupWithMembers {
    val isCreator = group.ownerUuid == selfUuid

    return GroupWithMembers(
        uuid = group.uuid,
        title = group.title,
        description = group.description,
        currency = group.currency,
        groupMembers = groupMembers.map { it.toGroupMember() },
        isLeft = group.isLeft,
        hasPendingChange = hasPendingChange,
        // A creator is never removed from their own group, so is never left in it either.
        canChangeMembers = isCreator && group.mlsGroupId != null,
        canEdit = isCreator,
        canVouchForMembers = !group.isLeft && group.mlsGroupId != null && !isCreatorBlocked,
        // The creator as this device recorded them, not the server's owner, which a deletion of
        // the creator's account hands to another member: that member is offered the leave, which
        // the server refuses its owner. It reads as no answer, and the sync booked for it leaves
        // the leave owed without sending it again, see group.GroupSync.leaveDeclined. The group
        // shows as left on their device, a group nobody can change again anyway, see
        // group.GroupLeaver.
        canLeave = !group.isLeft && !isCreator,
    )
}
