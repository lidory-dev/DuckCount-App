package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MemberRefusal
import app.lidory.duckcount.groupList.model.MembershipChangeError
import app.lidory.duckcount.groupList.model.MembershipChangeResult
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
import group.GroupMembershipError as SharedGroupMembershipError
import group.MembershipChangeOutcome as SharedMembershipChangeOutcome

fun GroupDb.toGroupListModel(): Group = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toGroupListModel(),
    isLeft = isLeft,
)

fun GroupWithMembersDb.toGroupListModel(): GroupWithMembers = GroupWithMembers(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency.toGroupListModel(),
    members = groupMembers.map {
        it.toGroupListModel()
    },
    isLeft = isLeft,
    hasPendingChange = hasPendingChange,
    canChangeMembers = canChangeMembers,
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
        fullNames = namesOf(userUuids = userUuids, memberUuids = memberUuids, fullNameOf = fullNameOf),
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

/**
 * What came of a change to a group's members that did not fail: made, or sent again until the
 * server takes it.
 */
fun SharedMembershipChangeOutcome.toMembershipChangeResult(): MembershipChangeResult = when (this) {
    SharedMembershipChangeOutcome.MADE -> MembershipChangeResult.Made
    SharedMembershipChangeOutcome.PENDING -> MembershipChangeResult.Pending
}

/**
 * Maps a failed change to a group's members to the reason the screen surfaces, as for a creation,
 * see [toGroupCreationError]: who it was about, by the names [fullNameOf] reads for them, in the
 * order of [memberUuids], the order they were picked in, or else what kept it from going through.
 *
 * What went wrong with the user's own storage or the library is folded into
 * [GroupCreationFailure.UNKNOWN], as is nobody being signed in, as for a creation. So is what the
 * group's settings offer no change for: a group the user did not create, was removed from, or
 * that this device does not hold, see [GroupWithMembersDb.canChangeMembers], one with a change
 * waiting on the server, see [GroupWithMembersDb.hasPendingChange], and more members than the
 * picker lets the user pick.
 */
suspend fun SharedGroupMembershipError.toMembershipChangeError(
    isOnline: Boolean,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): MembershipChangeError {
    suspend fun members(refusal: MemberRefusal, userUuids: Set<String>) = MembershipChangeError.Members(
        refusal = refusal,
        fullNames = namesOf(userUuids = userUuids, memberUuids = memberUuids, fullNameOf = fullNameOf),
    )

    return when (this) {
        is SharedGroupMembershipError.NotContacts -> members(refusal = MemberRefusal.NOT_CONTACTS, userUuids = userUuids)

        is SharedGroupMembershipError.IdentityChanged ->
            members(refusal = MemberRefusal.IDENTITY_CHANGED, userUuids = userUuids)

        is SharedGroupMembershipError.Unreachable -> members(refusal = MemberRefusal.UNREACHABLE, userUuids = userUuids)

        is SharedGroupMembershipError.Deleted -> members(refusal = MemberRefusal.DELETED, userUuids = userUuids)

        is SharedGroupMembershipError.Network ->
            MembershipChangeError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        is SharedGroupMembershipError.Contacts ->
            MembershipChangeError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        is SharedGroupMembershipError.Identity ->
            MembershipChangeError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

        SharedGroupMembershipError.NoSession,
        SharedGroupMembershipError.NotCreator,
        SharedGroupMembershipError.NotHeld,
        SharedGroupMembershipError.ChangePending,
        SharedGroupMembershipError.TooManyMembers,
        SharedGroupMembershipError.Storage,
        SharedGroupMembershipError.Crypto,
            -> MembershipChangeError.Failure(GroupCreationFailure.UNKNOWN)
    }
}

/**
 * The names [fullNameOf] reads for [userUuids], in the order of [memberUuids], the order the
 * screen lists or picked them in: the shared errors name them in no particular order.
 */
private suspend fun namesOf(
    userUuids: Set<String>,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): List<String> = userUuids
    .sortedBy { memberUuids.indexOf(it) }
    .map { userUuid ->
        // Every one of them picked from the contacts listed with their name, see
        // getInvitableUsersFiltered, whose row only goes with the session.
        checkNotNull(fullNameOf(userUuid)) { "No name for the member $userUuid" }
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
 * of the server refusing the group, or the change to its members, which the app never sends it as
 * it would. An answer that never came is no failure at all: the group, or the change, is sent again
 * until one does, see `group.GroupCreator` and `group.GroupMembershipChanger`.
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
