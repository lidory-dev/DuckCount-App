package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import database.domain.Currency as CurrencyDb
import database.domain.Group as GroupDb
import database.domain.GroupMember as GroupMemberDb
import database.domain.GroupWithMembers as GroupWithMembersDb
import database.domain.User as UserDb

fun GroupDb.toGroupListModel(): Group = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toGroupListModel(),
)

fun Group.toDb(): GroupDb = GroupDb(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toDb(),
)

fun GroupWithMembers.toDb(): GroupWithMembersDb = GroupWithMembersDb(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toDb(),
    groupMembers = members.map {
        it.toDb()
    },
)

fun GroupWithMembersDb.toGroupListModel(): GroupWithMembers = GroupWithMembers(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toGroupListModel(),
    members = groupMembers.map {
        it.toGroupListModel()
    },
)

fun Currency.toDb(): CurrencyDb = CurrencyDb(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)

fun CurrencyDb.toGroupListModel(): Currency = Currency(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)

fun GroupMember.toDb(): GroupMemberDb = GroupMemberDb(
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)

fun GroupMemberDb.toGroupListModel(): GroupMember = GroupMember(
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)

fun User.toDb(): UserDb = UserDb(
    uuid = uuid,
    username = username,
    fullName = fullName,
)

fun UserDb.toGroupListModel(): User = User(
    uuid = uuid,
    username = username,
    fullName = fullName,
)
