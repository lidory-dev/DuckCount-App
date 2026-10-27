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
 * read alongside, see [database.data.local.dao.GroupDao.hasPendingChange].
 */
fun GroupWithMembersEntity.toGroupWithMembers(selfUuid: String?, hasPendingChange: Boolean): GroupWithMembers {
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
    )
}
