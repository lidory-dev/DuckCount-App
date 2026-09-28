package contact

import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/** Why a contact request could not be accepted or refused. */
sealed interface AnswerContactRequestError : Error {

    /**
     * The request is no longer waiting for the user: its sender deleted their account, or
     * accepted the user's own request to them, which clears this one.
     */
    data object NotFound : AnswerContactRequestError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : AnswerContactRequestError

    /** The profile encryption key to hand over could not be read from the vault. */
    data class Vault(val error: VaultError) : AnswerContactRequestError

    /** The answer could not be encrypted to the group the request created. */
    data object Crypto : AnswerContactRequestError

    /**
     * The sender's MLS identity in the request's group is not the one pinned for them since
     * the request was opened. Either they legitimately got a new identity, or somebody is
     * impersonating them, and nothing here can tell which.
     */
    data object IdentityChanged : AnswerContactRequestError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : AnswerContactRequestError
}
