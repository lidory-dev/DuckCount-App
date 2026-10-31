package group

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why writing an expense of a group did not go through, see [GroupExpenseWriter]: creating one,
 * editing, deleting or restoring one, or recording a settlement. None of them leaves anything
 * written on this device but [Conflict], after which the latest version the server holds is
 * shown, when it opens.
 */
sealed interface GroupExpenseWriteError : Error {

    /** Nobody is signed in, so there is nobody to write the expense as. */
    data object NoSession : GroupExpenseWriteError

    /**
     * The user is no longer a member of the group: removed, or left, see
     * [database.data.local.entity.GroupEntity.isLeft], or the server says so, which the next group
     * sync reads. The group stays read-only, with what the user had of it. Or this device holds no
     * such group at all, its creation refused, say.
     */
    data object NotMember : GroupExpenseWriteError

    /**
     * The group's creation is waiting on the server, which would find no group to write in: nothing
     * is written in a group until it exists there, so that one whose creation is refused leaves no
     * expense behind it.
     */
    data object CreationPending : GroupExpenseWriteError

    /**
     * This device shows no version of the expense being edited, deleted or restored, or the edit
     * names none it was built on, see [database.domain.ExpenseWithBeneficiaries.revision].
     */
    data object NotShown : GroupExpenseWriteError

    /**
     * A settlement, which only its recipient, the one member it was paid to, records, and only they
     * delete; nobody edits one, nor restores it once deleted, see [mayBeFollowed] and
     * [SignedGroupExpenseDeletion.isSignedBy]. Every member's device refuses such a version, or such
     * a deletion flag, so none is sent.
     */
    data object SettlementLocked : GroupExpenseWriteError

    /**
     * The expense had every edit it takes, see
     * [app.lidory.duckcount.ExpenseLimits.MAX_GROUP_EXPENSE_EDITS]: this device shows it at its
     * last revision, see [LAST_GROUP_EXPENSE_REVISION], so nothing was sent, or the server holds it
     * there, by another member's edit, or by one every member's device refuses. Nothing was written,
     * and no edit of it ever will be; it is still deleted and restored, which are no edit.
     */
    data object EditLimitReached : GroupExpenseWriteError

    /** The user's MLS identity, which signs the version, could not be opened. */
    data class Identity(val error: MlsIdentityError) : GroupExpenseWriteError

    /**
     * The expense could not be given a UUID, signed or sealed: a session UUID that does not parse, a
     * key of the wrong size or a failed signature, all bugs rather than states.
     */
    data object Crypto : GroupExpenseWriteError

    /**
     * The server takes writes in the group under a newer generation of its key than this device
     * holds, and syncing the group did not bring it: a Commit leading there could not be read yet,
     * or this device cannot read it, as for a group brought back after a log-out, see
     * [GroupKeyChainError.NotHeld]. Nothing was written.
     */
    data object KeyNotCurrent : GroupExpenseWriteError

    /**
     * The group waits for its owner: this device refused a Commit every member's device refuses,
     * and stays in the epoch before it while the server is past it, see
     * [database.data.local.entity.GroupEntity.isAwaitingOwner], so that nothing it seals is taken.
     * The group's creator replaces that Commit as their device next syncs, see [GroupSync], and the
     * group can be written in again once this device read their Commit. Nothing was written.
     */
    data object AwaitingOwner : GroupExpenseWriteError

    /**
     * Another member changed the expense meanwhile: the server holds a later version than the one
     * edited, which is not this very edit, sent before and taken, its answer lost, the edit form
     * having been open on the one before it as the case may be. This device shows that version once
     * it opens, which the expense can be changed again over. Or, for a deletion or a restore,
     * another member's flag came first, and another again once it was sent after that one, the
     * expense not showing as asked.
     */
    data object Conflict : GroupExpenseWriteError

    /**
     * The server's limit on the group expenses one user writes is reached, every group together,
     * and lets a write through again in [retryAfterSeconds]. Nothing was written.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupExpenseWriteError

    /**
     * The server could not be reached, or refused the call. A version it took whose answer was lost
     * is shown once the next sync lists it.
     */
    data class Network(val error: NetworkError) : GroupExpenseWriteError
}
