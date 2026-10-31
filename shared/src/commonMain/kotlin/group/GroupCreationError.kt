package group

import contact.ContactSyncError
import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/**
 * Why a group could not be created. Nothing is left behind by any of them: neither the group nor
 * its MLS state, and nothing on the server.
 *
 * The ones naming members name every member it applies to, so that the user can be told about
 * all of them at once rather than one attempt at a time.
 */
sealed interface GroupCreationError : Error {

    /** Nobody is signed in, so there is nobody to create the group as. */
    data object NoSession : GroupCreationError

    /**
     * Who is not one of the user's contacts, or no longer, or one this device pinned no identity
     * for yet, never having reached them over MLS: building the group would pin them from
     * whichever key package the server served, letting it decide who they are.
     */
    data class NotContacts(val userUuids: Set<String>) : GroupCreationError

    /**
     * Whose key package carries a signature key other than the one this device pinned for them,
     * or whom the vault pins yet another one for, which blocks them, see
     * [database.data.local.entity.IdentityConflictEntity]. Either they legitimately got a new
     * identity, or somebody is impersonating them, and nothing here can tell which.
     *
     * The library stops at the first such member, so this names one alone unless they were
     * blocked already.
     */
    data class IdentityChanged(val userUuids: Set<String>) : GroupCreationError

    /**
     * Who has no key package to be invited with: they never published one, or their account is
     * gone, which the server answers the same way, or what it served is not one this build can
     * use, one whose lifetime has run out included, see [GroupCreator].
     */
    data class Unreachable(val userUuids: Set<String>) : GroupCreationError

    /** Who the server says deleted their account, so could never join, see [GroupCreationAnswer.DeletedInvitees]. */
    data class Deleted(val userUuids: Set<String>) : GroupCreationError

    /**
     * The user's contacts could not be brought back from the vault. Until they are, this device can
     * tell neither who they are, nor which identity is theirs.
     */
    data class Contacts(val error: ContactSyncError) : GroupCreationError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupCreationError

    /**
     * The key ring could not be read, which the creator's copy of the group's key is sealed under,
     * see [GroupKeyCopies.sealer]: the group is not created without it, the copy going with its
     * creation.
     */
    data class Vault(val error: VaultError) : GroupCreationError

    /** The user's own storage failed, so the same creation can be tried again later. */
    data object Storage : GroupCreationError

    /** The library could not build the group. */
    data object Crypto : GroupCreationError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : GroupCreationError
}
