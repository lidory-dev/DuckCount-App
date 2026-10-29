package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationFailure
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.LeaveResult
import app.lidory.duckcount.groupList.model.MemberRefusal
import app.lidory.duckcount.groupList.model.MemberRelation
import app.lidory.duckcount.groupList.model.MembershipChangeError
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.User
import contact.ContactRelation
import contact.ContactSyncError
import contact.SendContactRequestError
import mls.MlsIdentityError
import util.NetworkError
import util.Result
import vault.VaultError
import database.domain.Currency as CurrencyDb
import database.domain.Group as GroupDb
import database.domain.GroupMember as GroupMemberDb
import database.domain.GroupWithMembers as GroupWithMembersDb
import database.domain.User as UserDb
import group.GroupCreationError as SharedGroupCreationError
import group.GroupLeaveError as SharedGroupLeaveError
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
    canVouchForMembers = canVouchForMembers,
    canLeave = canLeave,
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

        is SharedGroupCreationError.Vault -> GroupCreationError.Failure(error.toGroupCreationFailure(isOnline = isOnline))

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
 * [GroupCreationFailure.UNKNOWN], as is nobody being signed in, as for a creation, and the device
 * locking as the change waited for a sync, as for a leave, see [toLeaveResult]. So is what the
 * group's settings offer no change for: a group the user did not create, is no longer in, or
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
        SharedGroupMembershipError.DeviceLocked,
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
 * What the group's settings are told of the user leaving it: left once this device recorded the
 * leave, whatever the server did, see `group.GroupLeaver`, or else nothing written, for a reason
 * folded into [GroupCreationFailure.UNKNOWN] whichever it was, nothing the user can act on: nobody
 * signed in, the device locking as the leave waited for a sync, no such group on this device, the
 * user's storage failing, or the group being one they created, which its settings never offer to
 * leave, see [GroupWithMembersDb.canLeave].
 */
fun Result<Unit, SharedGroupLeaveError>.toLeaveResult(): LeaveResult = when (this) {
    is Result.Success -> LeaveResult.Left

    is Result.Error -> when (error) {
        SharedGroupLeaveError.NoSession,
        SharedGroupLeaveError.DeviceLocked,
        SharedGroupLeaveError.NotHeld,
        SharedGroupLeaveError.IsCreator,
        SharedGroupLeaveError.Storage,
            -> LeaveResult.Failed(GroupCreationFailure.UNKNOWN)
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
    VaultError.MalformedMemberProfileBook,
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

/** Where the user stands with a member of the group, as their sheet tells it. */
fun ContactRelation.toMemberRelation(): MemberRelation = when (this) {
    ContactRelation.CONTACT -> MemberRelation.CONTACT
    ContactRelation.REQUEST_RECEIVED -> MemberRelation.REQUEST_RECEIVED
    ContactRelation.REQUEST_SENT -> MemberRelation.REQUEST_SENT
    ContactRelation.NONE -> MemberRelation.NONE
}

/**
 * Maps a failed contact request to a member of the group to the reason their sheet surfaces, as
 * for one to the holder of a username, see `toAddContactError`.
 *
 * What went wrong with the user's own key material, in the vault or in the MLS client, is folded
 * into [ContactRequestError.UNKNOWN]: nothing the user can act on, and nothing worth explaining
 * to them in detail. Network failures along the way are reported as such, whichever call they
 * came from.
 *
 * [isOnline] corrects a [NetworkError.UNREACHABLE] that is really airplane mode.
 */
fun SendContactRequestError.toContactRequestError(isOnline: Boolean): ContactRequestError = when (this) {
    SendContactRequestError.NotFound -> ContactRequestError.NOT_FOUND
    SendContactRequestError.AlreadyContact -> ContactRequestError.ALREADY_CONTACT
    SendContactRequestError.AlreadyPending -> ContactRequestError.ALREADY_PENDING
    SendContactRequestError.RateLimited -> ContactRequestError.RATE_LIMITED
    SendContactRequestError.IdentityChanged -> ContactRequestError.IDENTITY_CHANGED
    SendContactRequestError.NotInGroup -> ContactRequestError.NOT_IN_GROUP

    is SendContactRequestError.Network -> error.toContactRequestError(isOnline = isOnline)

    is SendContactRequestError.Vault -> error.toContactRequestError(isOnline = isOnline)

    is SendContactRequestError.Identity -> error.toContactRequestError(isOnline = isOnline)

    is SendContactRequestError.Contacts -> error.toContactRequestError(isOnline = isOnline)

    SendContactRequestError.Crypto -> ContactRequestError.UNKNOWN

    // A member is addressed by their UUID, never by a username, and the user never has a sheet
    // of their own.
    SendContactRequestError.InvalidUsername,
    SendContactRequestError.Self,
        -> ContactRequestError.UNKNOWN
}

/** Restoring the contacts fails on the vault or on the MLS client, reported as above. */
private fun ContactSyncError.toContactRequestError(isOnline: Boolean): ContactRequestError = when (this) {
    is ContactSyncError.Vault -> error.toContactRequestError(isOnline = isOnline)

    is ContactSyncError.Identity -> error.toContactRequestError(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
    is ContactSyncError.Network,
        -> ContactRequestError.UNKNOWN
}

private fun MlsIdentityError.toContactRequestError(isOnline: Boolean): ContactRequestError = when (this) {
    is MlsIdentityError.Vault -> error.toContactRequestError(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> ContactRequestError.UNKNOWN
}

private fun VaultError.toContactRequestError(isOnline: Boolean): ContactRequestError = when (this) {
    is VaultError.Network -> error.toContactRequestError(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> ContactRequestError.UNKNOWN
}

/**
 * The network failures left once the shared layer has mapped the key package's and the contact
 * route's own refusals (not found, already pending, rate limited).
 */
private fun NetworkError.toContactRequestError(isOnline: Boolean): ContactRequestError = when (this) {
    NetworkError.NO_INTERNET -> ContactRequestError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ContactRequestError.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) ContactRequestError.SERVER_UNREACHABLE else ContactRequestError.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ContactRequestError.UNKNOWN
}
