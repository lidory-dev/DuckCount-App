package group

import contact.ContactSyncError
import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why members could not be added to a group, or removed from it, see [GroupMembershipChanger].
 * None of them leaves the change made: the server holds no part of it, and what this device built
 * of it is discarded, or by the next sync if the user's storage fails to.
 *
 * The ones naming members name every member it applies to, so that the user can be told about
 * all of them at once rather than one attempt at a time, as for a creation, see
 * [GroupCreationError].
 */
sealed interface GroupMembershipError : Error {

    /** Nobody is signed in, so there is nobody to change the group as. */
    data object NoSession : GroupMembershipError

    /**
     * The device locked before the change could be built, as it waited for a sync under way, say:
     * who is signed in, the user's MLS identity and the key to their profile are read with keys a
     * locked device cannot use, see [GroupSyncError.DeviceLocked].
     */
    data object DeviceLocked : GroupMembershipError

    /**
     * The user did not create the group, and only its creator adds and removes members: the
     * members' devices take Commits from nobody else, see
     * [database.data.local.entity.GroupEntity.ownerUuid].
     */
    data object NotCreator : GroupMembershipError

    /**
     * This device holds no MLS state for the group to build a Commit in: it was brought back after
     * a log-out, which takes every MLS group with it, or it is no longer on this device at all, its
     * creation refused.
     */
    data object NotHeld : GroupMembershipError

    /**
     * A change to the group is waiting on the server: its creation, or another change to its
     * members, which the next sync sends again until it is taken. The library builds one Commit
     * at a time per group, and one built over a change the server may hold would be refused by
     * every member.
     */
    data object ChangePending : GroupMembershipError

    /**
     * Who is not one of the user's contacts, or no longer, or one this device pinned no identity
     * for yet, as for a creation, see [GroupCreationError.NotContacts].
     */
    data class NotContacts(val userUuids: Set<String>) : GroupMembershipError

    /**
     * Whose key package carries a signature key other than the one this device pinned for them, or
     * whom the vault blocks, as for a creation, see [GroupCreationError.IdentityChanged].
     */
    data class IdentityChanged(val userUuids: Set<String>) : GroupMembershipError

    /**
     * Who has no key package to be added with, or one this build cannot use, as for a creation,
     * see [GroupCreationError.Unreachable].
     */
    data class Unreachable(val userUuids: Set<String>) : GroupMembershipError

    /** Who the server says deleted their account, so could never join, see [GroupMembershipAnswer.DeletedMembers]. */
    data class Deleted(val userUuids: Set<String>) : GroupMembershipError

    /** The group would hold more than [app.lidory.duckcount.GroupLimits.MAX_MEMBERS] members. */
    data object TooManyMembers : GroupMembershipError

    /** The user's contacts could not be brought back from the vault, as for a creation. */
    data class Contacts(val error: ContactSyncError) : GroupMembershipError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupMembershipError

    /** The user's own storage failed, so the same change can be tried again later. */
    data object Storage : GroupMembershipError

    /** The library could not build the change. */
    data object Crypto : GroupMembershipError

    /** The server could not be reached, or refused the change. */
    data class Network(val error: NetworkError) : GroupMembershipError
}
