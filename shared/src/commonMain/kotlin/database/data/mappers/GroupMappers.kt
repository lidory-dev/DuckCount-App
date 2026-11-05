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
    isDeleted = isDeleted,
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
        isDeleted = group.isDeleted,
        hasPendingChange = hasPendingChange,
        // A creator is never removed from their own group, so is left in it only once it is gone.
        canChangeMembers = isCreator && !group.isLeft && group.mlsGroupId != null,
        canEdit = isCreator && !group.isLeft,
        canVouchForMembers = !group.isLeft && group.mlsGroupId != null && !isCreatorBlocked,
        // The server refuses its owner the leave: their way out is deleting it.
        canLeave = !group.isLeft && !isCreator,
        canDelete = !group.isLeft && isCreator,
    )
}
