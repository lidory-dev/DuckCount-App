package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupWithMembersEntity
import database.data.remote.GroupDto
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.Group
import database.domain.GroupWithMembers

// --- Placeholders until group expenses are end-to-end encrypted ---------------
// A group's title, description and currency are sealed fields the server cannot
// read, and nothing here holds the key that opens them yet: unlike a one-time
// expense, a group's key is meant to come out of `VaultEntryKey.GROUP_MASTER_KEY`,
// which nothing writes. Until it does, a group that round-trips through the server
// comes back with these in place of what it said.

private fun placeholderGroupTitle(): String = "Fake Title"

private fun placeholderGroupDescription(): String = "Fake Description"

private fun placeholderGroupCurrency(): Currency = CurrencyCatalog.defaultCurrency
// ------------------------------------------------------------------------------

fun GroupDto.toGroupWithMembersEntity() = GroupWithMembersEntity(
    group = Group(
        uuid = uuid,
        title = placeholderGroupTitle(),
        description = placeholderGroupDescription(),
        currency = placeholderGroupCurrency(),
    ).toGroupEntity(),
    groupMembers = members.map {
        it.toGroupMemberEntity(groupUuid = uuid)
    },
)

fun Group.toGroupEntity() = GroupEntity(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
)

fun GroupEntity.toGroup() = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
)

fun GroupWithMembers.toGroupWithMembersEntity() = GroupWithMembersEntity(
    group = Group(
        uuid = uuid,
        title = title,
        description = description,
        currency = currency,
    ).toGroupEntity(),
    groupMembers = groupMembers.map { it.toGroupMemberEntity() },
)

fun GroupWithMembersEntity.toGroupWithMembers() = GroupWithMembers(
    uuid = group.uuid,
    title = group.title,
    description = group.description,
    currency = group.currency,
    groupMembers = groupMembers.map { it.toGroupMember() },
)
