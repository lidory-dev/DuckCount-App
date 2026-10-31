package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.StoredGroupExpense
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.domain.Beneficiary
import database.domain.ExpenseWithBeneficiaries
import database.domain.trimmed
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.GroupExpenseId
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.newGroupExpenseId
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Writes the expenses of the user's groups, as they ask from a group's screens: creating one,
 * editing, deleting or restoring one, and recording that somebody paid them back, a settlement.
 * Any member writes any expense of their group but a settlement, which its recipient alone records
 * and deletes, and nobody edits or restores, see [mayBeFollowedBy]: what every member's device
 * refuses is refused here, before anything is sent. Saving needs the server, as for a one-time
 * expense: nothing is written on this device until the server took it, and then only the version
 * it took.
 *
 * Each write is a version, signed by the user and sealed under the group's current key, see
 * [sealGroupExpense], at the revision after the one it is built on: the one the edit form was read
 * from, see [ExpenseWithBeneficiaries.revision], or for a deletion and a restore the one this device
 * shows. A deleted expense stays listed, struck through, and counts in no balance. Its title and
 * description are taken without the whitespace around them, as a one-time expense's are, see
 * [trimmed], and its currency is the group's.
 *
 * What the server can answer, and what comes of it, see [GroupExpenseWriteAnswer]:
 * - A create's UUID is derived from the user's and a nonce, see `newGroupExpenseId` in
 *   lib-duckcount, so one whose answer is lost is sent again, once, with the same nonce, and hears
 *   it was stored already if it was, which is taken as stored. One whose second answer is lost too
 *   is the failure it is, and saving it again then makes another expense.
 * - A version sealed under a key of an epoch the group left since, Commits this device had yet to
 *   read, is sealed again, once, under the key the group sync derives as it reads them, see
 *   [GroupSync], and sent again. Should the sync leave the group where it was, the write fails:
 *   for want of a key, or, when this device refused a Commit every member's device refused, see
 *   [GroupEntity.isAwaitingOwner], as waiting for the group's owner, whose device replaces it.
 * - A version built on one the server holds no longer hears which revision it holds, and the
 *   group's expenses are synced, see [GroupExpenseSync], which shows that version: when it is this
 *   very version, sent before and taken, its answer lost, at the revision it was sent at or the
 *   later one it was written over a refused version at, see below, the write is made, and so is a
 *   deletion or a restore another member's made first; otherwise another member's came first, see
 *   [GroupExpenseWriteError.Conflict]. So does an edit built on a version this device no longer
 *   shows, which another member's replaced as the form was open.
 * - Unless the version the server holds is one this device refused, see
 *   [GroupExpenseSyncPass.refused], which it goes on showing the one before of: the write is then
 *   made, once, over it, at the revision after it, as every member's device that refused it too
 *   takes it. Nothing else would ever change the expense again, every write built on what they
 *   show being refused for a version none of them can show.
 *
 * Nothing is written in a group whose creation the server has yet to take, see
 * [GroupExpenseWriteError.CreationPending], nor in one the user is no longer in, which stays
 * read-only with what they had of it.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] and [hasPendingCreation] are [GroupDao.get]
 * and [GroupDao.hasPendingCreation], [shown] and [store] are [ExpenseDao.getGroupExpense] and
 * [ExpenseDao.storeGroupExpenses], [client] is [MlsIdentityRepository.client], [create] and
 * [update] are [GroupExpenseApi.create] and [GroupExpenseApi.update], [syncGroups] is
 * [GroupSync.sync], what it read told about through [GroupSyncAnnouncer], [syncExpenses] is
 * [GroupExpenseSync.syncGroup] for the user, who the write read before it was sent, and [newId] is
 * `newGroupExpenseId` in lib-duckcount, or what a test stands in for any of them.
 */
class GroupExpenseWriter internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingCreation: suspend (groupUuid: String) -> Boolean,
    private val shown: suspend (groupUuid: String, expenseUuid: String) -> ExpenseWithBeneficiariesEntity?,
    private val store: suspend (
        groupUuid: String,
        expenses: List<ExpenseWithBeneficiariesEntity>,
    ) -> List<StoredGroupExpense>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val create: suspend (groupUuid: String, body: CreateGroupExpenseBody) -> GroupExpenseWriteAnswer,
    private val update: suspend (
        groupUuid: String,
        expenseUuid: String,
        body: UpdateGroupExpenseBody,
    ) -> GroupExpenseWriteAnswer,
    private val syncGroups: suspend () -> Unit,
    private val syncExpenses: suspend (groupUuid: String, selfUuid: String) -> GroupExpenseSyncPass,
    private val newId: (authorUuid: String) -> GroupExpenseId?,
) {
    constructor(
        groupExpenseApi: GroupExpenseApi,
        groupDao: GroupDao,
        expenseDao: ExpenseDao,
        groupSync: GroupSync,
        groupSyncAnnouncer: GroupSyncAnnouncer,
        groupExpenseSync: GroupExpenseSync,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        group = groupDao::get,
        hasPendingCreation = groupDao::hasPendingCreation,
        shown = expenseDao::getGroupExpense,
        store = expenseDao::storeGroupExpenses,
        client = mlsIdentityRepository::client,
        create = groupExpenseApi::create,
        update = groupExpenseApi::update,
        // The contacts are not synced first, as the push sync does: a Welcome from somebody this
        // device has yet to take in as a contact is left waiting rather than declined, and nothing
        // here waits on it. What it does read of the other groups is read once, so it is told about
        // here, or never.
        syncGroups = { groupSyncAnnouncer.announce(pass = groupSync.sync(contactsTakenIn = false)) },
        syncExpenses = groupExpenseSync::syncGroup,
        newId = ::newGroupExpenseId,
    )

    /** Creates [expense] in the group [groupUuid], see [GroupExpenseWriter]. */
    suspend fun create(groupUuid: String, expense: ExpenseWithBeneficiaries): EmptyResult<GroupExpenseWriteError> {
        val selfUuid = userUuid() ?: return Result.Error(GroupExpenseWriteError.NoSession)

        val held = when (val read = writable(groupUuid = groupUuid)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        // Recorded by the member paid back alone, see [SignedGroupExpense.isSignedBy].
        if (expense.isSettlement && expense.beneficiaries.singleOrNull()?.userUuid != selfUuid) {
            return Result.Error(GroupExpenseWriteError.SettlementLocked)
        }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupExpenseWriteError.Identity(opened.error))
        }

        // Known before the post, so that what is sealed and signed can name it.
        val id = newId(selfUuid) ?: return Result.Error(GroupExpenseWriteError.Crypto)

        val created = expense.trimmed().inGroup(group = held, expenseUuid = id.uuid).copy(isDeleted = false)

        return write(
            groupUuid = groupUuid,
            expense = created,
            revision = FIRST_GROUP_EXPENSE_REVISION,
            nonce = id.nonce,
            selfUuid = selfUuid,
            client = client,
        )
    }

    /**
     * Records that [payerUuid] paid the user [amount] back in the group [groupUuid], at
     * [timestamp]: a settlement, which the user, its recipient, alone records, see
     * [GroupExpenseWriter], paid by [payerUuid] to the user as its one beneficiary.
     */
    suspend fun recordSettlement(
        groupUuid: String,
        payerUuid: String,
        amount: String,
        timestamp: String,
    ): EmptyResult<GroupExpenseWriteError> {
        val selfUuid = userUuid() ?: return Result.Error(GroupExpenseWriteError.NoSession)

        val held = when (val read = writable(groupUuid = groupUuid)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return create(
            groupUuid = groupUuid,
            expense = ExpenseWithBeneficiaries(
                uuid = "",
                title = "",
                timestamp = timestamp,
                description = "",
                amount = amount,
                creditorUuid = payerUuid,
                isSettlement = true,
                currency = held.currency,
                groupUuid = groupUuid,
                beneficiaries = listOf(
                    Beneficiary(expenseUuid = "", userUuid = selfUuid, share = amount, isShareFixed = true),
                ),
            ),
        )
    }

    /**
     * Edits the expense [expense] names of the group [groupUuid] into what it says, see
     * [GroupExpenseWriter]: its title and description, amount and date, who paid and who benefits,
     * as the version after [ExpenseWithBeneficiaries.revision], the one the form was read from.
     * Whether it is deleted stays as this device shows it, and so does whether it is a settlement,
     * which nobody edits.
     */
    suspend fun edit(groupUuid: String, expense: ExpenseWithBeneficiaries): EmptyResult<GroupExpenseWriteError> {
        val baseRevision = expense.revision ?: return Result.Error(GroupExpenseWriteError.NotShown)

        return replace(groupUuid = groupUuid, expenseUuid = expense.uuid, baseRevision = baseRevision) { held, shown, _ ->
            if (shown.expense.isSettlement) return@replace null

            expense.trimmed()
                .inGroup(group = held, expenseUuid = shown.expense.uuid)
                .copy(isSettlement = false, isDeleted = shown.expense.isDeleted)
        }
    }

    /**
     * Deletes the expense [expenseUuid] of the group [groupUuid]: a version saying so, see
     * [GroupExpenseWriter]. A settlement only its recipient deletes. One shown deleted already is
     * left as it is, with nothing sent.
     */
    suspend fun delete(groupUuid: String, expenseUuid: String): EmptyResult<GroupExpenseWriteError> =
        replace(groupUuid = groupUuid, expenseUuid = expenseUuid, isDeleted = true) { _, shown, selfUuid ->
            if (shown.expense.isSettlement && shown.beneficiaries.singleOrNull()?.userUuid != selfUuid) {
                return@replace null
            }

            shown.toExpenseWithBeneficiaries().copy(isDeleted = true)
        }

    /**
     * Restores the deleted expense [expenseUuid] of the group [groupUuid]: a version saying so, see
     * [GroupExpenseWriter]. Never a settlement, which another settlement records again instead. One
     * shown not deleted is left as it is, with nothing sent.
     */
    suspend fun restore(groupUuid: String, expenseUuid: String): EmptyResult<GroupExpenseWriteError> =
        replace(groupUuid = groupUuid, expenseUuid = expenseUuid, isDeleted = false) { _, shown, _ ->
            if (shown.expense.isSettlement) return@replace null

            shown.toExpenseWithBeneficiaries().copy(isDeleted = false)
        }

    /**
     * Writes the version [next] makes of the expense [expenseUuid] of the group [groupUuid] out of
     * the one shown, at the revision after [baseRevision], or after the one shown when null, or
     * refuses it as a settlement's, [next] answering null, see
     * [GroupExpenseWriteError.SettlementLocked]. Nothing is sent when the expense is shown already as
     * [isDeleted] says it is to be, for a deletion or a restore.
     */
    private suspend fun replace(
        groupUuid: String,
        expenseUuid: String,
        baseRevision: Long? = null,
        isDeleted: Boolean? = null,
        next: suspend (
            held: GroupEntity,
            shown: ExpenseWithBeneficiariesEntity,
            selfUuid: String,
        ) -> ExpenseWithBeneficiaries?,
    ): EmptyResult<GroupExpenseWriteError> {
        val selfUuid = userUuid() ?: return Result.Error(GroupExpenseWriteError.NoSession)

        val held = when (val read = writable(groupUuid = groupUuid)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val shown = shown(groupUuid, expenseUuid) ?: return Result.Error(GroupExpenseWriteError.NotShown)
        val revision = baseRevision ?: shown.expense.revision ?: return Result.Error(GroupExpenseWriteError.NotShown)

        if (isDeleted != null && shown.expense.isDeleted == isDeleted) return Result.Success(Unit)

        val version = next(held, shown, selfUuid) ?: return Result.Error(GroupExpenseWriteError.SettlementLocked)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupExpenseWriteError.Identity(opened.error))
        }

        return write(
            groupUuid = groupUuid,
            expense = version,
            revision = revision + 1,
            nonce = null,
            selfUuid = selfUuid,
            client = client,
            isDeleted = isDeleted,
        )
    }

    /**
     * The group [groupUuid] as this device holds it, when something may be written in it: the user
     * is still in it, and its creation is not waiting on the server.
     */
    private suspend fun writable(groupUuid: String): Result<GroupEntity, GroupExpenseWriteError> {
        val held = group(groupUuid)?.takeIf { !it.isLeft } ?: return Result.Error(GroupExpenseWriteError.NotMember)

        if (hasPendingCreation(groupUuid)) return Result.Error(GroupExpenseWriteError.CreationPending)

        return Result.Success(held)
    }

    /**
     * Seals [expense] as [revision], signed by [client], the user [selfUuid]'s identity, under the
     * group [groupUuid]'s current key, sends it, a create with [nonce] or else an update, a deletion
     * or a restore when [isDeleted] says which, and stores it once the server took it, see
     * [GroupExpenseWriter] for what else it answers.
     */
    private suspend fun write(
        groupUuid: String,
        expense: ExpenseWithBeneficiaries,
        revision: Long,
        nonce: String?,
        selfUuid: String,
        client: MlsClient,
        isDeleted: Boolean? = null,
    ): EmptyResult<GroupExpenseWriteError> {
        var sentRevision = revision
        var isSealedAgain = false
        var isSentAgain = false
        var isWrittenOver = false

        while (true) {
            val held = group(groupUuid)?.takeIf { !it.isLeft } ?: return Result.Error(GroupExpenseWriteError.NotMember)

            val content = expense.sealGroupExpense(
                groupUuid = groupUuid,
                revision = sentRevision,
                groupKey = held.groupKey,
                client = client,
                authorUuid = selfUuid,
            ) ?: return Result.Error(GroupExpenseWriteError.Crypto)

            val answer = if (nonce != null) {
                create(
                    groupUuid,
                    CreateGroupExpenseBody(nonce = nonce, keyGeneration = held.keyGeneration, content = content),
                )
            } else {
                update(
                    groupUuid,
                    expense.uuid,
                    UpdateGroupExpenseBody(revision = sentRevision, keyGeneration = held.keyGeneration, content = content),
                )
            }

            val sent = OpenedGroupExpense(expense = expense, revision = sentRevision, authorUuid = selfUuid).toEntity()

            when (answer) {
                GroupExpenseWriteAnswer.Written -> {
                    // Stored over nothing newer: a sync may have stored it already, from the change
                    // the write made in the user's own feed of the group.
                    store(groupUuid, listOf(sent))

                    return Result.Success(Unit)
                }

                // The create sent again below, the first send's answer lost: the server answers no
                // update so, an update carrying no nonce.
                GroupExpenseWriteAnswer.AlreadyExists -> {
                    store(groupUuid, listOf(sent))

                    return Result.Success(Unit)
                }

                is GroupExpenseWriteAnswer.KeyGenerationNotCurrent -> {
                    if (isSealedAgain) return Result.Error(GroupExpenseWriteError.KeyNotCurrent)

                    isSealedAgain = true

                    syncGroups()

                    val synced = group(groupUuid)?.takeIf { !it.isLeft }
                        ?: return Result.Error(GroupExpenseWriteError.NotMember)

                    // Nothing new to seal it under: the same refusal would come back.
                    if (synced.keyGeneration == held.keyGeneration) {
                        return Result.Error(
                            if (synced.isAwaitingOwner) {
                                GroupExpenseWriteError.AwaitingOwner
                            } else {
                                GroupExpenseWriteError.KeyNotCurrent
                            },
                        )
                    }
                }

                is GroupExpenseWriteAnswer.RevisionNotNext -> {
                    // As the user read before the write: the device may have locked since.
                    val pass = syncExpenses(groupUuid, selfUuid)

                    // Over a version this device refused, built on the one it shows still: once, as
                    // the one after it would be refused for a version written meanwhile, which
                    // another member's device may have shown.
                    val isOverRefused = !isWrittenOver &&
                        pass.refused[expense.uuid] == answer.revision &&
                        shown(groupUuid, expense.uuid)?.expense?.revision == sentRevision - 1

                    if (!isOverRefused) {
                        return conflictOver(
                            groupUuid = groupUuid,
                            sent = sent,
                            storedRevision = answer.revision,
                            selfUuid = selfUuid,
                            pass = pass,
                            isDeleted = isDeleted,
                        )
                    }

                    isWrittenOver = true
                    sentRevision = answer.revision + 1
                }

                GroupExpenseWriteAnswer.GroupNotFound -> return Result.Error(GroupExpenseWriteError.NotMember)

                is GroupExpenseWriteAnswer.RateLimited -> return Result.Error(
                    GroupExpenseWriteError.RateLimited(retryAfterSeconds = answer.retryAfterSeconds),
                )

                is GroupExpenseWriteAnswer.Failed -> {
                    // An update whose answer is lost needs none of this: sent again, it hears the
                    // revision it took, or the one it was written over as, see conflictOver.
                    if (nonce == null || isSentAgain || answer.error in NEVER_STORED) {
                        return Result.Error(GroupExpenseWriteError.Network(answer.error))
                    }

                    isSentAgain = true
                }
            }
        }
    }

    /**
     * What an update refused for [storedRevision], the revision the server holds, comes to, once
     * [pass] synced the group [groupUuid]'s expenses, which shows the version it holds when it opens:
     * made, when that version is [sent], signed by the user, [selfUuid], and saying what it says, at
     * its revision, this very update sent before and taken, its answer lost, or at a later one, as
     * it was taken once written over a version every device refused, see [write]; or when it
     * leaves the expense deleted or not as [isDeleted] says it was to be, for a deletion or a
     * restore, another member's having made it first; or else [GroupExpenseWriteError.Conflict].
     * When the server holds [sent]'s revision, or the one after it, as it would once [sent] was
     * written over a refused version, and the sync could not reach it, which of the two it is
     * cannot be told, and the sync's failure is answered.
     */
    private suspend fun conflictOver(
        groupUuid: String,
        sent: ExpenseWithBeneficiariesEntity,
        storedRevision: Long,
        selfUuid: String,
        pass: GroupExpenseSyncPass,
        isDeleted: Boolean?,
    ): EmptyResult<GroupExpenseWriteError> {
        val revision = checkNotNull(sent.expense.revision)
        val after = shown(groupUuid, sent.expense.uuid)

        val isSent = after != null &&
            (after.expense.revision ?: 0L) >= revision &&
            after.expense.authorUuid == selfUuid &&
            after.expense.isDeleted == sent.expense.isDeleted &&
            after.saysWhat(sent)

        if (isSent) return Result.Success(Unit)

        // Shown otherwise as this was sent: what the sync stored since made it so, as good as its
        // being shown so already, see replace.
        if (isDeleted != null && after?.expense?.isDeleted == isDeleted) return Result.Success(Unit)

        val failure = pass.failure

        // Written over the refused version directly above the one this device shows, see write, it
        // is at the revision after the one sent.
        if ((storedRevision == revision || storedRevision == revision + 1) &&
            failure is GroupExpenseSyncError.Network &&
            (after?.expense?.revision ?: 0L) < revision
        ) {
            return Result.Error(GroupExpenseWriteError.Network(failure.error))
        }

        return Result.Error(GroupExpenseWriteError.Conflict)
    }
}

/**
 * The failures of a create that prove the server did not store it: the host never resolved, so
 * nothing was sent; the token was turned away before the handler ran; the body was refused before
 * anything was written; or the proxy in front of the server answered as it is redeployed. Every
 * other one, a timeout, a dropped connection, a `5xx` the proxy may return after the commit, leaves
 * open that it was.
 */
private val NEVER_STORED = setOf(
    NetworkError.NO_INTERNET,
    NetworkError.UNAUTHORIZED,
    NetworkError.BAD_REQUEST,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.NOT_FOUND,
)

/**
 * This expense as the one [expenseUuid] of [group]: in its currency, which every expense of a group
 * is in, each beneficiary named as of it.
 */
private fun ExpenseWithBeneficiaries.inGroup(group: GroupEntity, expenseUuid: String) = copy(
    uuid = expenseUuid,
    currency = group.currency,
    groupUuid = group.uuid,
    beneficiaries = beneficiaries.map { it.copy(expenseUuid = expenseUuid) },
)
