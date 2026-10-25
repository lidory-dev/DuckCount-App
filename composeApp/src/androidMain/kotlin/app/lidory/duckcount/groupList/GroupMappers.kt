package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MemberRefusal
import app.lidory.duckcount.groupList.model.User
import contact.ContactSyncError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import database.domain.Currency as CurrencyDb
import database.domain.Group as GroupDb
import database.domain.GroupMember as GroupMemberDb
import database.domain.GroupWithMembers as GroupWithMembersDb
import database.domain.User as UserDb
import group.GroupCreationError as SharedGroupCreationError

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

/**
 * Maps a failed group creation to the reason the form surfaces: who it was about, by the names
 * [fullNameOf] reads for them, in the order of [memberUuids], the form's, or else what kept it
 * from going through.
 *
 * What went wrong with the user's own key material or storage is folded into
 * [GroupCreationFailure.UNKNOWN], as for a contact request, see `toAddContactError`: nothing the
 * user can act on. So is nobody being signed in, which no screen of the group list is shown
 * without. Network failures along the way are reported as such, whichever call they came from.
 *
 * [isOnline] corrects a [NetworkError.UNREACHABLE] that is really airplane mode.
 */
suspend fun SharedGroupCreationError.toGroupCreationError(
    isOnline: Boolean,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): GroupCreationError {
    suspend fun members(refusal: MemberRefusal, userUuids: Set<String>) = GroupCreationError.Members(
        refusal = refusal,
        fullNames = userUuids
            .sortedBy { memberUuids.indexOf(it) }
            .map { userUuid ->
                // Every one of them picked from the contacts listed with their name, see
                // getInvitableUsersFiltered, whose row only goes with the session.
                checkNotNull(fullNameOf(userUuid)) { "No name for the member $userUuid" }
            },
    )

    return when (this) {
        is SharedGroupCreationError.NotContacts -> members(refusal = MemberRefusal.NOT_CONTACTS, userUuids = userUuids)

        is SharedGroupCreationError.IdentityChanged ->
            members(refusal = MemberRefusal.IDENTITY_CHANGED, userUuids = userUuids)

        is SharedGroupCreationError.Unreachable -> members(refusal = MemberRefusal.UNREACHABLE, userUuids = userUuids)

        is SharedGroupCreationError.Deleted -> members(refusal = MemberRefusal.DELETED, userUuids = userUuids)

        is SharedGroupCreationError.Network -> GroupCreationError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        is SharedGroupCreationError.Contacts -> GroupCreationError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        is SharedGroupCreationError.Identity -> GroupCreationError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        SharedGroupCreationError.NoSession,
        SharedGroupCreationError.Storage,
        SharedGroupCreationError.Crypto,
            -> GroupCreationError.Failure(GroupCreationFailure.UNKNOWN)
    }
}

/** Restoring the contacts fails on the vault or on the MLS client, reported as above. */
private fun ContactSyncError.toGroupCreationFailure(isOnline: Boolean): GroupCreationFailure = when (this) {
    is ContactSyncError.Vault -> error.toGroupCreationFailure(isOnline = isOnline)

    is ContactSyncError.Identity -> error.toGroupCreationFailure(isOnline = isOnline)

    is ContactSyncError.Network -> error.toGroupCreationFailure(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
        -> GroupCreationFailure.UNKNOWN
}

private fun MlsIdentityError.toGroupCreationFailure(isOnline: Boolean): GroupCreationFailure = when (this) {
    is MlsIdentityError.Vault -> error.toGroupCreationFailure(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> GroupCreationFailure.UNKNOWN
}

private fun VaultError.toGroupCreationFailure(isOnline: Boolean): GroupCreationFailure = when (this) {
    is VaultError.Network -> error.toGroupCreationFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> GroupCreationFailure.UNKNOWN
}

/**
 * The network failures of asking for the invitees' key packages, or the vault, along the way, or
 * of the server refusing the group, which the form never sends it as it would. An answer that never
 * came is no failure at all: the group is sent again until one does, see `group.GroupCreator`.
 */
private fun NetworkError.toGroupCreationFailure(isOnline: Boolean): GroupCreationFailure = when (this) {
    NetworkError.NO_INTERNET -> GroupCreationFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> GroupCreationFailure.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) GroupCreationFailure.SERVER_UNREACHABLE else GroupCreationFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> GroupCreationFailure.UNKNOWN
}
