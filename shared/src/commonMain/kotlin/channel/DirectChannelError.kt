package channel

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/** Why this device could not get a channel to a peer. */
sealed interface DirectChannelError : Error {

    /** Nobody is signed in, so there is no identity to build a channel from. */
    data object NoSession : DirectChannelError

    /**
     * The peer asked for is the user themselves.
     *
     * A caller bug rather than a state, and reported rather than thrown because the cost of
     * letting it through is real: fetching a key package spends one, so a device that asked
     * itself would quietly eat its own pool and leave real peers the last-resort one.
     */
    data object Self : DirectChannelError

    /** The user's MLS identity could not be opened. */
    data class Identity(val error: MlsIdentityError) : DirectChannelError

    /**
     * This device has pinned no identity for the peer, so a channel would trust whatever key
     * package the server served for them. Only adding a contact pins somebody new.
     */
    data object NotPinned : DirectChannelError

    /**
     * The key package the server served for the peer is signed by a different identity than
     * the one this device pinned for them, or the vault pins yet another one for them, which
     * blocks them, see [database.data.local.entity.IdentityConflictEntity].
     *
     * Either they lost their MLS signature key, or somebody is standing in for them. Not
     * something to work around silently.
     */
    data object IdentityChanged : DirectChannelError

    /**
     * There is no reaching the peer: they have published no key package, their account is
     * gone, or what the server served is not one this build can use.
     */
    data object Unreachable : DirectChannelError

    /** The user's own storage failed, so the same call can be made again later. */
    data object Storage : DirectChannelError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : DirectChannelError
}
