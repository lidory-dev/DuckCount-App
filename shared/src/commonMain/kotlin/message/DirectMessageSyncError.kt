package message

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why taking in the messages waiting for the user stopped short.
 *
 * Nothing here is final: a message that was not acknowledged is handed back by the next
 * call.
 */
sealed interface DirectMessageSyncError : Error {

    /** The user's own MLS client could not be opened, so nothing could be decrypted. */
    data class Identity(val error: MlsIdentityError) : DirectMessageSyncError

    /**
     * The user's own storage failed. Nothing was consumed, so trying again later may work.
     */
    data object Storage : DirectMessageSyncError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : DirectMessageSyncError
}
