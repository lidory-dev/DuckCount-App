package group

import contact.ContactRelation
import contact.ContactSyncError
import contact.SendContactRequestError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError

// What the iOS group screens are told of what went wrong, as Android's `:feature:groupList` models
// tell it and its app module's `GroupMappers` maps it, which live in Android-only modules. Flat enums,
// and small sealed outcomes where a case carries something, for Swift to switch over: a shared
// sealed error reaches it as a protocol and a class per case, which nothing makes it handle.

/**
 * Why a change to a group could not be made, told in a sentence of its own, as Android's
 * `GroupCreationFailure`. What went wrong with the user's own key material or storage, or the library,
 * is [UNKNOWN]: nothing the user can act on.
 */
enum class GroupFailure {
    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /** The user is no longer a member of the group. */
    GROUP_LEFT,

    /** The server took too many changes from the user. */
    RATE_LIMITED,

    /** The group waits for its owner to come online before taking this. */
    AWAITING_OWNER,

    UNKNOWN,
}

/** Why the server refused people a group was created with, or that were being added to it. */
enum class MemberRefusal {
    /** They are no contacts of the user. */
    NOT_CONTACTS,

    /** The user's safety number with them changed. */
    IDENTITY_CHANGED,

    /** They cannot be invited for now: no key package to welcome them with. */
    UNREACHABLE,

    /** They deleted their account. */
    DELETED,
}

/** What creating a group gave, as Android's `GroupCreationResult`. */
sealed interface GroupCreationOutcome {

    /** The group is kept on the device, and sent to the server until it takes it. */
    data class Created(val groupUuid: String) : GroupCreationOutcome

    data class Failed(val failure: GroupFailure) : GroupCreationOutcome

    /** The server refused [fullNames], in the order the form lists them. */
    data class Refused(val refusal: MemberRefusal, val fullNames: List<String>) : GroupCreationOutcome
}

/** What adding members to a group, or removing them, gave, as Android's `MembershipChangeResult`. */
sealed interface MemberChangeOutcome {

    /** The server took it. */
    data object Made : MemberChangeOutcome

    /** It is kept on the device, and sent as soon as the server can take it. */
    data object Pending : MemberChangeOutcome

    data class Failed(val failure: GroupFailure) : MemberChangeOutcome

    /** The server refused [fullNames], in the order the picker lists them. */
    data class Refused(val refusal: MemberRefusal, val fullNames: List<String>) : MemberChangeOutcome
}

/**
 * Why a group's title and description were not edited, as Android's `GroupEditError`, its
 * `Failure` flattened into the network cases and [UNKNOWN].
 */
enum class GroupEditFailure {
    /** Its last change has not reached the server yet. */
    CHANGE_PENDING,

    /** Somebody edited it in the meantime: saving again writes over what they wrote. */
    OUTDATED,

    /** It already has this title and description. */
    UNCHANGED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/** Why a group's history could not be read, as Android's `GroupHistoryError`, flattened likewise. */
enum class GroupHistoryFailure {
    /** The phone locked before it could be read. */
    DEVICE_LOCKED,

    /** It cannot be read on this device yet. */
    UNREADABLE,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/** What reading a group's history gave. */
sealed interface GroupHistoryOutcome {

    /** Its versions, newest first. */
    data class Versions(val versions: List<GroupInformationVersion>) : GroupHistoryOutcome

    data class Unavailable(val failure: GroupHistoryFailure) : GroupHistoryOutcome
}

/** Where the user stands with a member of one of their groups, as Android's groupList `MemberRelation`. */
enum class MemberRelation {
    CONTACT,
    REQUEST_RECEIVED,
    REQUEST_SENT,

    /**
     * None of the above. Not `NONE`, which Swift gets as `none` and reads as nil once the value is
     * optional, which every value a flow hands it is.
     */
    UNRELATED,
}

/** Why a contact request to a member of a group was not sent, as Android's groupList `ContactRequestError`. */
enum class MemberContactRequestFailure {
    /** The member cannot receive one right now. */
    NOT_FOUND,

    ALREADY_CONTACT,
    ALREADY_PENDING,
    RATE_LIMITED,

    /** The user's safety number with the member changed. */
    IDENTITY_CHANGED,

    /** The member's identity cannot be confirmed from this group on this device. */
    NOT_IN_GROUP,

    /** The user blocked the member. */
    BLOCKED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/**
 * A failed group creation as the form tells it: who it was about, by the names [fullNameOf] reads
 * for them, in the order of [memberUuids], the form's, or else what kept it from going through, as
 * Android's `toGroupCreationError`. Nobody being signed in, the user's storage and the library are
 * [GroupFailure.UNKNOWN].
 */
internal suspend fun GroupCreationError.toOutcome(
    isOnline: Boolean,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): GroupCreationOutcome {
    suspend fun refused(refusal: MemberRefusal, userUuids: Set<String>) = GroupCreationOutcome.Refused(
        refusal = refusal,
        fullNames = namesOf(userUuids = userUuids, memberUuids = memberUuids, fullNameOf = fullNameOf),
    )

    return when (this) {
        is GroupCreationError.NotContacts -> refused(refusal = MemberRefusal.NOT_CONTACTS, userUuids = userUuids)
        is GroupCreationError.IdentityChanged -> refused(refusal = MemberRefusal.IDENTITY_CHANGED, userUuids = userUuids)
        is GroupCreationError.Unreachable -> refused(refusal = MemberRefusal.UNREACHABLE, userUuids = userUuids)
        is GroupCreationError.Deleted -> refused(refusal = MemberRefusal.DELETED, userUuids = userUuids)
        is GroupCreationError.Network -> GroupCreationOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupCreationError.Contacts -> GroupCreationOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupCreationError.Identity -> GroupCreationOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupCreationError.Vault -> GroupCreationOutcome.Failed(error.toGroupFailure(isOnline = isOnline))

        GroupCreationError.NoSession,
        GroupCreationError.Storage,
        GroupCreationError.Crypto,
            -> GroupCreationOutcome.Failed(GroupFailure.UNKNOWN)
    }
}

/**
 * A failed change to a group's members as the screen tells it, as for a creation, see [toOutcome],
 * and as Android's `toMembershipChangeError`: what the settings offer no change for, a change another
 * member's Commit came before, the device locking and the user's storage are [GroupFailure.UNKNOWN].
 */
internal suspend fun GroupMembershipError.toOutcome(
    isOnline: Boolean,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): MemberChangeOutcome {
    suspend fun refused(refusal: MemberRefusal, userUuids: Set<String>) = MemberChangeOutcome.Refused(
        refusal = refusal,
        fullNames = namesOf(userUuids = userUuids, memberUuids = memberUuids, fullNameOf = fullNameOf),
    )

    return when (this) {
        is GroupMembershipError.NotContacts -> refused(refusal = MemberRefusal.NOT_CONTACTS, userUuids = userUuids)
        is GroupMembershipError.IdentityChanged -> refused(refusal = MemberRefusal.IDENTITY_CHANGED, userUuids = userUuids)
        is GroupMembershipError.Unreachable -> refused(refusal = MemberRefusal.UNREACHABLE, userUuids = userUuids)
        is GroupMembershipError.Deleted -> refused(refusal = MemberRefusal.DELETED, userUuids = userUuids)
        is GroupMembershipError.Network -> MemberChangeOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupMembershipError.Contacts -> MemberChangeOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupMembershipError.Identity -> MemberChangeOutcome.Failed(error.toGroupFailure(isOnline = isOnline))
        is GroupMembershipError.Vault -> MemberChangeOutcome.Failed(error.toGroupFailure(isOnline = isOnline))

        GroupMembershipError.NoSession,
        GroupMembershipError.DeviceLocked,
        GroupMembershipError.NotCreator,
        GroupMembershipError.NotHeld,
        GroupMembershipError.ChangePending,
        GroupMembershipError.TooManyMembers,
        GroupMembershipError.Outdated,
        GroupMembershipError.Storage,
        GroupMembershipError.Crypto,
            -> MemberChangeOutcome.Failed(GroupFailure.UNKNOWN)
    }
}

/** What came of a change to a group's members that did not fail. */
internal fun MembershipChangeOutcome.toOutcome(): MemberChangeOutcome = when (this) {
    MembershipChangeOutcome.MADE -> MemberChangeOutcome.Made
    MembershipChangeOutcome.PENDING -> MemberChangeOutcome.Pending
}

/**
 * An edit that was not saved, as Android's `toGroupEditError`: a change on its way to the server,
 * an edit the device had yet to read, or nothing to change, or else what kept it from going
 * through. The device locking, an edit sealed under a key the group left, and what the settings
 * offer no edit of are [GroupEditFailure.UNKNOWN].
 */
internal fun GroupEditError.toFailure(isOnline: Boolean): GroupEditFailure = when (this) {
    GroupEditError.ChangePending -> GroupEditFailure.CHANGE_PENDING
    GroupEditError.Outdated -> GroupEditFailure.OUTDATED
    GroupEditError.Unchanged -> GroupEditFailure.UNCHANGED
    is GroupEditError.Network -> error.toGroupFailure(isOnline = isOnline).toEditFailure()
    is GroupEditError.Identity -> error.toGroupFailure(isOnline = isOnline).toEditFailure()

    GroupEditError.NoSession,
    GroupEditError.DeviceLocked,
    GroupEditError.NotHeld,
    GroupEditError.NotCreator,
    GroupEditError.KeyNotCurrent,
    GroupEditError.Crypto,
        -> GroupEditFailure.UNKNOWN
}

/** A history that could not be read, as Android's `toGroupHistoryError`. */
internal fun GroupHistoryError.toFailure(isOnline: Boolean): GroupHistoryFailure = when (this) {
    GroupHistoryError.DeviceLocked -> GroupHistoryFailure.DEVICE_LOCKED
    GroupHistoryError.Unreadable -> GroupHistoryFailure.UNREADABLE
    is GroupHistoryError.Network -> error.toGroupFailure(isOnline = isOnline).toHistoryFailure()
    is GroupHistoryError.Identity -> error.toGroupFailure(isOnline = isOnline).toHistoryFailure()

    GroupHistoryError.NoSession,
    GroupHistoryError.NotMember,
    GroupHistoryError.Storage,
        -> GroupHistoryFailure.UNKNOWN
}

/**
 * A deletion the server did not take, as Android's `toDeleteResult`: what kept it from being
 * reached, or else [GroupFailure.UNKNOWN], nothing else being anything the user can act on.
 */
internal fun GroupDeleteError.toFailure(isOnline: Boolean): GroupFailure = when (this) {
    is GroupDeleteError.Network -> error.toGroupFailure(isOnline = isOnline)

    GroupDeleteError.NoSession,
    GroupDeleteError.DeviceLocked,
    GroupDeleteError.NotHeld,
    GroupDeleteError.NotOwner,
    GroupDeleteError.StillMember,
    GroupDeleteError.ChangePending,
    GroupDeleteError.Storage,
        -> GroupFailure.UNKNOWN
}

/**
 * Why a settlement the user recorded did not go through, as Android's
 * `GroupExpenseWriteError.toGroupCreationFailure`: what kept the server from being reached, a group
 * they are no longer in, the server's limit, and a group waiting for its owner, or else
 * [GroupFailure.UNKNOWN].
 */
internal fun GroupExpenseWriteError.toGroupFailure(isOnline: Boolean): GroupFailure = when (this) {
    is GroupExpenseWriteError.Network -> error.toGroupFailure(isOnline = isOnline)
    is GroupExpenseWriteError.Identity -> error.toGroupFailure(isOnline = isOnline)
    GroupExpenseWriteError.NotMember -> GroupFailure.GROUP_LEFT
    is GroupExpenseWriteError.RateLimited -> GroupFailure.RATE_LIMITED
    GroupExpenseWriteError.AwaitingOwner -> GroupFailure.AWAITING_OWNER

    GroupExpenseWriteError.NoSession,
    GroupExpenseWriteError.CreationPending,
    GroupExpenseWriteError.NotShown,
    GroupExpenseWriteError.SettlementLocked,
    GroupExpenseWriteError.Crypto,
    GroupExpenseWriteError.KeyNotCurrent,
    GroupExpenseWriteError.Conflict,
    GroupExpenseWriteError.EditLimitReached,
        -> GroupFailure.UNKNOWN
}

/** Where the user stands with a member, as their sheet tells it. */
internal fun ContactRelation.toMemberRelation(): MemberRelation = when (this) {
    ContactRelation.CONTACT -> MemberRelation.CONTACT
    ContactRelation.REQUEST_RECEIVED -> MemberRelation.REQUEST_RECEIVED
    ContactRelation.REQUEST_SENT -> MemberRelation.REQUEST_SENT
    ContactRelation.NONE -> MemberRelation.UNRELATED
}

/**
 * A failed contact request to a member of a group, as Android's `toContactRequestError`: what went
 * wrong with the user's own key material is [MemberContactRequestFailure.UNKNOWN], and a member
 * being addressed by their UUID, never a username, neither of the username's refusals comes up.
 */
internal fun SendContactRequestError.toMemberFailure(isOnline: Boolean): MemberContactRequestFailure = when (this) {
    SendContactRequestError.NotFound -> MemberContactRequestFailure.NOT_FOUND
    SendContactRequestError.AlreadyContact -> MemberContactRequestFailure.ALREADY_CONTACT
    SendContactRequestError.AlreadyPending -> MemberContactRequestFailure.ALREADY_PENDING
    SendContactRequestError.RateLimited -> MemberContactRequestFailure.RATE_LIMITED
    SendContactRequestError.IdentityChanged -> MemberContactRequestFailure.IDENTITY_CHANGED
    SendContactRequestError.NotInGroup -> MemberContactRequestFailure.NOT_IN_GROUP
    SendContactRequestError.Blocked -> MemberContactRequestFailure.BLOCKED
    is SendContactRequestError.Network -> error.toMemberFailure(isOnline = isOnline)
    is SendContactRequestError.Vault -> error.toMemberFailure(isOnline = isOnline)
    is SendContactRequestError.Identity -> error.toMemberFailure(isOnline = isOnline)
    is SendContactRequestError.Contacts -> error.toMemberFailure(isOnline = isOnline)

    SendContactRequestError.Crypto,
    SendContactRequestError.InvalidUsername,
    SendContactRequestError.Self,
        -> MemberContactRequestFailure.UNKNOWN
}

/**
 * The names [fullNameOf] reads for [userUuids], in the order of [memberUuids], the order the screen
 * lists or picked them in: the shared errors name them in no particular order. Each was picked from
 * contacts listed with their name; one missing anyway is left out rather than ending the app, as
 * Android's `checkNotNull` would on iOS.
 */
private suspend fun namesOf(
    userUuids: Set<String>,
    memberUuids: List<String>,
    fullNameOf: suspend (userUuid: String) -> String?,
): List<String> = userUuids
    .sortedBy { memberUuids.indexOf(it) }
    .mapNotNull { fullNameOf(it) }

private fun GroupFailure.toEditFailure(): GroupEditFailure = when (this) {
    GroupFailure.NO_INTERNET -> GroupEditFailure.NO_INTERNET
    GroupFailure.TIMEOUT -> GroupEditFailure.TIMEOUT
    GroupFailure.SERVER_UNREACHABLE -> GroupEditFailure.SERVER_UNREACHABLE
    else -> GroupEditFailure.UNKNOWN
}

private fun GroupFailure.toHistoryFailure(): GroupHistoryFailure = when (this) {
    GroupFailure.NO_INTERNET -> GroupHistoryFailure.NO_INTERNET
    GroupFailure.TIMEOUT -> GroupHistoryFailure.TIMEOUT
    GroupFailure.SERVER_UNREACHABLE -> GroupHistoryFailure.SERVER_UNREACHABLE
    else -> GroupHistoryFailure.UNKNOWN
}

/** Restoring the contacts fails on the vault or on the MLS client, told as above. */
private fun ContactSyncError.toGroupFailure(isOnline: Boolean): GroupFailure = when (this) {
    is ContactSyncError.Vault -> error.toGroupFailure(isOnline = isOnline)
    is ContactSyncError.Identity -> error.toGroupFailure(isOnline = isOnline)
    is ContactSyncError.Network -> error.toGroupFailure(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
        -> GroupFailure.UNKNOWN
}

private fun MlsIdentityError.toGroupFailure(isOnline: Boolean): GroupFailure = when (this) {
    is MlsIdentityError.Vault -> error.toGroupFailure(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> GroupFailure.UNKNOWN
}

private fun VaultError.toGroupFailure(isOnline: Boolean): GroupFailure = when (this) {
    is VaultError.Network -> error.toGroupFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> GroupFailure.UNKNOWN
}

/**
 * The network failures of a change to a group, as Android's `NetworkError.toGroupCreationFailure`,
 * [isOnline] telling airplane mode from a server out of reach: Ktor's Darwin engine reports both as
 * a connection that failed.
 */
private fun NetworkError.toGroupFailure(isOnline: Boolean): GroupFailure = when (this) {
    NetworkError.NO_INTERNET -> GroupFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> GroupFailure.TIMEOUT
    NetworkError.UNREACHABLE -> if (isOnline) GroupFailure.SERVER_UNREACHABLE else GroupFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> GroupFailure.UNKNOWN
}

private fun ContactSyncError.toMemberFailure(isOnline: Boolean): MemberContactRequestFailure = when (this) {
    is ContactSyncError.Vault -> error.toMemberFailure(isOnline = isOnline)
    is ContactSyncError.Identity -> error.toMemberFailure(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
    is ContactSyncError.Network,
        -> MemberContactRequestFailure.UNKNOWN
}

private fun MlsIdentityError.toMemberFailure(isOnline: Boolean): MemberContactRequestFailure = when (this) {
    is MlsIdentityError.Vault -> error.toMemberFailure(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> MemberContactRequestFailure.UNKNOWN
}

private fun VaultError.toMemberFailure(isOnline: Boolean): MemberContactRequestFailure = when (this) {
    is VaultError.Network -> error.toMemberFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> MemberContactRequestFailure.UNKNOWN
}

private fun NetworkError.toMemberFailure(isOnline: Boolean): MemberContactRequestFailure = when (this) {
    NetworkError.NO_INTERNET -> MemberContactRequestFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> MemberContactRequestFailure.TIMEOUT
    NetworkError.UNREACHABLE ->
        if (isOnline) MemberContactRequestFailure.SERVER_UNREACHABLE else MemberContactRequestFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> MemberContactRequestFailure.UNKNOWN
}
