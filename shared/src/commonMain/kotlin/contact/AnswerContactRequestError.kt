package contact

import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/** Why a contact request could not be accepted or refused. */
sealed interface AnswerContactRequestError : Error {

    /**
     * The request is no longer waiting for the user, and their acceptance did not take it: it
     * was refused, cleared by its sender accepting the user's own request to them, or went
     * with the sender's account.
     */
    data object NotFound : AnswerContactRequestError

    /**
     * The request could not be refused: the user had accepted it already, without hearing
     * back, and the server says that acceptance took it. Its sender is made a contact rather
     * than dropped, now or by the next refresh, see
     * [database.data.local.entity.ContactRequestEntity.isAcceptanceSent].
     */
    data object AlreadyAccepted : AnswerContactRequestError

    /**
     * The user's contacts could not be brought back from the vault. Until they are, this
     * device cannot tell whether the sender is one of them, nor which identity is theirs.
     */
    data class Contacts(val error: ContactSyncError) : AnswerContactRequestError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : AnswerContactRequestError

    /** The profile encryption key to hand over could not be read from the vault. */
    data class Vault(val error: VaultError) : AnswerContactRequestError

    /** The answer could not be encrypted to the group the request created. */
    data object Crypto : AnswerContactRequestError

    /**
     * The sender's MLS identity in the request's group is not the one pinned for them since
     * the request was opened, or by the time the server took the user's acceptance, which
     * drops the request; or the vault pins yet another one for them, which blocks them, see
     * [database.data.local.entity.IdentityConflictEntity]. Either they legitimately got a
     * new identity, or somebody is impersonating them, and nothing here can tell which.
     */
    data object IdentityChanged : AnswerContactRequestError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : AnswerContactRequestError
}
