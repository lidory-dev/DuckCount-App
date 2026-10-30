package group

import util.Error
import util.NetworkError

/** Why a generation of a group's key could not be had, see [GroupKeyChain]. */
sealed interface GroupKeyChainError : Error {

    /**
     * This device holds no such group, or no key of the generation asked for nor of any after it:
     * the chain only leads back, and a later generation is the group's creator's to hand over, over
     * MLS, which the sync reads, see [GroupSync]. A group brought back after a log-out, which reads
     * nothing sent in it, never gets past the generation the user's copy held, see [GroupKeyCopies].
     */
    data object NotHeld : GroupKeyChainError

    /**
     * The user is no longer in the group, removed or left, see [database.data.local.entity.GroupEntity.isLeft],
     * and the server hands the chain to its current members alone: what this device holds of its
     * keys is all it ever will.
     */
    data object NotMember : GroupKeyChainError

    /**
     * The links the server keeps do not lead there: one is missing, or does not open under the key
     * after it as that link of that group, see [openKeyLink]. Every rotation is taken with the
     * link it makes, see [RotateGroupKeyBody.previousKey], so the server is holding one back, or
     * serving one it was never given.
     */
    data object Unreadable : GroupKeyChainError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : GroupKeyChainError
}
