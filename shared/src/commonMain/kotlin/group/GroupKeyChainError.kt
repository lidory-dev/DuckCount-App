package group

import util.Error
import util.NetworkError

/** Why a generation of a group's key could not be had, see [GroupKeyChain]. */
sealed interface GroupKeyChainError : Error {

    /**
     * This device holds no such group, or no key of the generation asked for nor of any after it:
     * the chain only leads back, and a later generation is derived as the group's Commits are read,
     * see [GroupSync]. A group brought back after a log-out, which reads nothing sent in it, gets no
     * further than the generation the user's copy held, see [GroupKeyCopies], until it is rejoined,
     * see [GroupRejoiner]: from the key of the epoch it rejoins at on, the chain leads back to the
     * generations it skipped, through the links its members publish there. Nor does anything sealed
     * at a generation a Commit nobody could apply led to, which the creator's replacement of it
     * undid, see [ReplaceGroupCommitBody]: no member holds its key.
     */
    data object NotHeld : GroupKeyChainError

    /**
     * The user is no longer in the group, removed or left, see [database.data.local.entity.GroupEntity.isLeft],
     * and the server hands the chain to its current members alone: what this device holds of its
     * keys is all it ever will.
     */
    data object NotMember : GroupKeyChainError

    /**
     * The links the server keeps do not lead there: none at a generation on the way opens under the
     * key after it as that link of that group, see [openKeyLink], to a key something sealed under
     * it opens under, see [recoverKeys]. Every Commit is taken with the link it makes, see
     * [AddGroupMembersBody.previousKey], and every member reaching its epoch publishes its own when
     * that one leads nowhere, so the server is holding them back, or serving ones it was never
     * given.
     */
    data object Unreadable : GroupKeyChainError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : GroupKeyChainError
}
