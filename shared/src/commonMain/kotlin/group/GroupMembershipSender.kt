package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.PendingMembershipChangeEntity
import message.DirectMessageSender
import message.ResendScheduler
import mls.EpochKey
import mls.GroupRoster
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.applyPendingCommitIn
import mls.deleteGroupOf
import mls.discardPendingCommitIn
import mls.epochKeyIn
import mls.holdsGroup
import mls.rosterOf
import push.WakeList
import uniffi.lib_duckcount.MemberInfo
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * How soon a change the server was not heard to answer, or that this device could not act on
 * once taken, is sent again: as soon as a creation is, see [GroupCreationSender], the members
 * waiting on it as the invitees of one do.
 */
private const val UNANSWERED_RESEND_SECONDS = 60L

/**
 * Sends a Commit of the user's to the members of a group, and acts on what the server answers: the
 * one place that decides what becomes of one, whether it is the first send, see
 * [GroupMembershipChanger] and [GroupRejoiner], or a resend at a later sync, see [resendPending].
 * The creator's additions and removals, any member's removal of those who departed, the creator's
 * replacement of a Commit nobody could apply, and any member's rejoin of a group a log-out took
 * the MLS state of, see [PendingMembershipChange].
 *
 * The change was saved before the first send, see [PendingMembershipChangeEntity], its Commit
 * pending in the library, so that whatever the answer, or none, the device holds what the server
 * may hold:
 * - The server holds the change, now or from an earlier send, see [GroupMembershipAnswer.Made]
 *   and [GroupMembershipAnswer.AlreadyMade]: the Commit is applied, the members are those the
 *   group holds then, the signature key each holds in its tree recorded, see
 *   [GroupMemberSignatureKeyEntity], who may wake the user rebuilt with them, see [WakeList], and
 *   the key of the epoch it took the group to becomes the group's, backed up already, the Commit
 *   having carried the user's copy of it, see [AddGroupMembersBody.keyCopy], as the change goes,
 *   see [GroupDao.takeCommittedKey]. The key it replaces is kept.
 * - The server refused it, writing nothing, see [GroupMembershipAnswer.DeletedMembers],
 *   [GroupMembershipAnswer.Refused] and [GroupMembershipAnswer.KeyGenerationNotCurrent]: the Commit
 *   is discarded and the change goes, the group as it was. Members named as deleted are acted on as
 *   any other answer naming them is, see [DirectMessageSender.receiveAccountDeletions]. A Commit
 *   refused for its epoch is one another member's Commit beat, which the group sync reads next:
 *   it decides its own changes again then, see [GroupSync], and an addition or a removal the user
 *   asked for that is refused so as it is sent again, rather than at the first send, which tells
 *   them, is kept and made anew, see [GroupMembershipChanger.remakeOutdatedUnderGroupsLock].
 * - The server has not taken it yet, see [GroupMembershipAnswer.RateLimited] and
 *   [GroupMembershipAnswer.Failed]: everything stays, and the next sync is booked for when it may
 *   go through, see [ResendScheduler]. But for a rejoin answered `404`, see [isRejoinNotFound],
 *   which waits for whatever syncs next.
 *
 * Every step can be cut short, the process killed, and taken up again by the next sync from where
 * it stopped: a Commit still pending is sent again, the server answering `409` for one it took; one
 * no longer pending, the change outliving it, was applied if the group is past the epoch it was
 * built in, see [PendingMembershipChangeEntity.commitEpoch], and discarded otherwise, the key of
 * the epoch it applied in derived then, the group still being in it.
 *
 * A rejoin is the exception, see [GroupRejoiner]: mls-rs builds a Commit from outside a group
 * applied, so nothing is ever pending in the library, and the group is past the epoch it was built
 * in from the start. Its nonce alone tells what became of it, so it is sent again for as long as
 * the library holds the state it was built in, see
 * [database.data.local.entity.MembershipChangeKind.REJOIN]. Taken, the members are those the state
 * holds, the key of its epoch becomes the group's, backed up already, as for any Commit of the
 * user's, and the next sync is booked, see [rejoined]. Refused, its state is deleted, then the
 * group's MLS id forgotten with the rejoin, see [GroupDao.forgetRejoin], the group brought back
 * after a log-out again, for the next sync to rejoin anew: a state found deleted as it is sent again
 * is one whose refusal the process did not live to finish with.
 *
 * To be called under [withGroupsLock], which it does not take itself, as for a creation.
 *
 * [addMembers], [removeMembers], [removeDeparted] and [replaceCommit] are those of [GroupApi],
 * [rejoin] is [GroupApi.rejoin], [group] is [GroupDao.get], [listPending] is
 * [GroupDao.getMembershipChanges], [dropPending], [markOutdated], [dropOutdated] and [forgetRejoin]
 * are [GroupDao.deleteMembershipChange], [GroupDao.markMembershipChangeOutdated],
 * [GroupDao.deleteOutdatedMembershipChange] and [GroupDao.forgetRejoin],
 * [replaceMembers] is [GroupDao.replaceMembers],
 * [recordSignatureKeys] is [GroupDao.upsertSignatureKeys], [rebuildWakeList] is
 * [WakeList.rebuild], [takeCommittedKey] is [GroupDao.takeCommittedKey], [client] is
 * [MlsIdentityRepository.client] and [receiveAccountDeletions] is
 * [DirectMessageSender.receiveAccountDeletions], or what a test stands in for any of them.
 */
class GroupMembershipSender internal constructor(
    private val addMembers: suspend (groupUuid: String, body: AddGroupMembersBody) -> GroupMembershipAnswer,
    private val removeMembers: suspend (groupUuid: String, body: RemoveGroupMembersBody) -> GroupMembershipAnswer,
    private val removeDeparted: suspend (groupUuid: String, body: RemoveDepartedMembersBody) -> GroupMembershipAnswer,
    private val replaceCommit: suspend (groupUuid: String, body: ReplaceGroupCommitBody) -> GroupMembershipAnswer,
    private val rejoin: suspend (groupUuid: String, body: RejoinGroupBody) -> GroupMembershipAnswer,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val listPending: suspend () -> List<PendingMembershipChangeEntity>,
    private val dropPending: suspend (groupUuid: String) -> Unit,
    private val markOutdated: suspend (groupUuid: String) -> Unit,
    private val dropOutdated: suspend (groupUuid: String) -> Unit,
    private val forgetRejoin: suspend (groupUuid: String) -> Unit,
    private val replaceMembers: suspend (groupUuid: String, members: List<GroupMemberEntity>) -> Unit,
    private val recordSignatureKeys: suspend (signatureKeys: List<GroupMemberSignatureKeyEntity>) -> Unit,
    private val rebuildWakeList: suspend () -> Unit,
    private val takeCommittedKey: suspend (key: GroupKeyEntity) -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val receiveAccountDeletions: suspend (userUuids: Collection<String>) -> Unit,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        directMessageSender: DirectMessageSender,
        wakeList: WakeList,
        resendScheduler: ResendScheduler,
    ) : this(
        addMembers = groupApi::addMembers,
        removeMembers = groupApi::removeMembers,
        removeDeparted = groupApi::removeDeparted,
        replaceCommit = groupApi::replaceCommit,
        rejoin = groupApi::rejoin,
        group = groupDao::get,
        listPending = groupDao::getMembershipChanges,
        dropPending = groupDao::deleteMembershipChange,
        markOutdated = groupDao::markMembershipChangeOutdated,
        dropOutdated = groupDao::deleteOutdatedMembershipChange,
        forgetRejoin = groupDao::forgetRejoin,
        replaceMembers = groupDao::replaceMembers,
        recordSignatureKeys = groupDao::upsertSignatureKeys,
        rebuildWakeList = wakeList::rebuild,
        takeCommittedKey = groupDao::takeCommittedKey,
        client = mlsIdentityRepository::client,
        receiveAccountDeletions = directMessageSender::receiveAccountDeletions,
        resendScheduler = resendScheduler,
    )

    /**
     * Sends [change] to the group [group], whose MLS state [client] holds, its Commit pending, or
     * applied already for a rejoin, and acts on the answer, see [GroupMembershipSender], which it
     * returns.
     */
    internal suspend fun send(
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ): GroupMembershipAnswer {
        val answer = answerTo(change = change)

        actOn(answer = answer, change = change, group = group, client = client)

        return answer
    }

    /** What the server answers [change], sent to it. */
    private suspend fun answerTo(change: PendingMembershipChange): GroupMembershipAnswer =
        when (change) {
            is PendingMembershipChange.Addition -> addMembers(change.groupUuid, change.body)
            is PendingMembershipChange.Removal -> removeMembers(change.groupUuid, change.body)
            is PendingMembershipChange.DepartedRemoval -> removeDeparted(change.groupUuid, change.body)
            is PendingMembershipChange.Replacement -> replaceCommit(change.groupUuid, change.body)
            is PendingMembershipChange.Rejoin -> rejoin(change.groupUuid, change.body)
        }

    /**
     * Acts on [answer], the server's to [change], the change pending for [group], see
     * [GroupMembershipSender].
     */
    private suspend fun actOn(
        answer: GroupMembershipAnswer,
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ) {
        when (answer) {
            GroupMembershipAnswer.Made, GroupMembershipAnswer.AlreadyMade ->
                if (change is PendingMembershipChange.Rejoin) {
                    rejoined(group = group, client = client)
                } else {
                    taken(group = group, client = client)
                }

            is GroupMembershipAnswer.DeletedMembers -> {
                refused(change = change, group = group, client = client)
                receiveAccountDeletions(answer.userUuids)
            }

            is GroupMembershipAnswer.KeyGenerationNotCurrent,
            is GroupMembershipAnswer.Refused,
            -> refused(change = change, group = group, client = client)

            is GroupMembershipAnswer.RateLimited -> resendScheduler.resendAfter(seconds = answer.retryAfterSeconds)

            is GroupMembershipAnswer.Failed ->
                if (!change.isRejoinNotFound(answer = answer)) {
                    resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)
                }
        }
    }

    /**
     * Books the resend of a change about to go out for the first time, as for a creation, see
     * [GroupCreationSender.bookResend]: one whose answer the process does not live to hear would
     * otherwise wait for whatever syncs next, and hold back every other change to its group.
     */
    suspend fun bookResend() = resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

    /**
     * Takes up every change the server has not been heard to take, or whose taking this device has
     * yet to act on, see [GroupMembershipSender], and stops at the first the server did not answer,
     * or refused for the limit, which every kind shares: the next one would fare no better, and
     * the resend booked for it goes for them all.
     *
     * Fails with what kept the first one from going through, the limit aside, which is no failure:
     * nothing needs doing but waiting for the resend. Nor is a rejoin answered `404`, see
     * [isRejoinNotFound], which is passed over for the next: it says nothing of the others, which
     * every pass would otherwise leave unsent for as long as the server keeps its rejoins shut.
     */
    suspend fun resendPending(): EmptyResult<GroupSyncError> {
        val pending = listPending()

        // Most syncs find nothing pending, and opening the client reads the vault.
        if (pending.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupSyncError.Identity(opened.error))
        }

        var failure: GroupSyncError? = null

        // One to be made again holds no Commit to send, see GroupMembershipChanger.
        for (change in pending.filterNot { it.isOutdated }.map { it.toChange() }) {
            val answer = try {
                resend(change = change, client = client)
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage

                continue
            }

            when {
                // See above: waits for the next sync, whatever wakes it.
                change.isRejoinNotFound(answer = answer) -> Unit

                answer is GroupMembershipAnswer.Failed -> return Result.Error(failure ?: GroupSyncError.Network(answer.error))

                answer is GroupMembershipAnswer.RateLimited -> break
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Settles every change pending for a group the server no longer lists, of those it does,
     * [listedUuids]: the user removed from it since, by its creator, or the group deleted, see
     * [GroupSync]. The server answers a change from somebody no longer a member with a `404`
     * whether or not it took it, which is no answer, see [membershipAnswerOf], so it would be sent
     * again for good, and the group, never read while a change is pending in it, see
     * [GroupSync.drainAll], never read for the Commit that removed the user.
     *
     * What became of it is told by the first Commit waiting for the user in the group that was built
     * in the epoch the change was built in or later, see [firstCommitEpochIn], read before anything
     * is acknowledged there: built in the epoch the change's Commit leads to, the server took that
     * one, and it is applied, see [taken]; built in the epoch it was built in, or none waiting,
     * another Commit got there first, or the group went, and it is discarded, see [discard]. Either
     * way the change goes, and the sync reads what waits there next, the Commit removing the user
     * among it. Nothing in the group is acknowledged while the change is pending, so every Commit the
     * server stored from the epoch the change was built in on waits there still, in order: behind
     * any read before the change was built whose acknowledgement did not go through, which is of an
     * earlier epoch. Fails with the first thing that kept one from being settled, which the next sync
     * tries again.
     *
     * A rejoin is forgotten whatever waits there, see [forget]: its Commit applied from the start,
     * there is nothing pending to apply or discard, and the user is out of the group whether or not
     * the server took it, the group sync then finding it brought back after a log-out and no longer
     * listed, which it marks left, see [GroupSync].
     */
    internal suspend fun settleUnlisted(
        listedUuids: Set<String>,
        firstCommitEpochIn: suspend (groupUuid: String, fromEpoch: Long) -> Result<Long?, NetworkError>,
    ): EmptyResult<GroupSyncError> {
        val unlisted = listPending().filter { it.groupUuid !in listedUuids }

        // Most syncs find nothing pending, and opening the client reads the vault.
        if (unlisted.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupSyncError.Identity(opened.error))
        }

        var failure: GroupSyncError? = null

        for (pendingChange in unlisted) {
            // Never to be made now: the user is no longer in the group.
            if (pendingChange.isOutdated) {
                dropOutdated(pendingChange.groupUuid)

                continue
            }

            val change = pendingChange.toChange()

            // Applied from the start, see above, and of no use: the user is out of the group.
            if (change is PendingMembershipChange.Rejoin) {
                forget(group = heldGroupOf(change = change), client = client)

                continue
            }

            val firstCommitEpoch = when (val read = firstCommitEpochIn(change.groupUuid, change.commitEpoch)) {
                is Result.Success -> read.data

                is Result.Error -> {
                    failure = failure ?: GroupSyncError.Network(read.error)

                    continue
                }
            }

            try {
                val group = heldGroupOf(change = change)

                val roster = client.rosterOf(mlsGroupId = group.heldMlsGroupId())

                when {
                    !roster.hasPendingCommit -> settled(change = change, group = group, client = client, roster = roster)
                    firstCommitEpoch == change.commitEpoch + 1 -> taken(group = group, client = client)
                    else -> discard(group = group, client = client)
                }
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Takes up [change] from where it was left, see [GroupMembershipSender], and returns what the
     * server answered, or null when nothing needed sending.
     *
     * Throws [MlsException] when the group could not be read.
     */
    private suspend fun resend(change: PendingMembershipChange, client: MlsClient): GroupMembershipAnswer? {
        val group = heldGroupOf(change = change)

        if (change is PendingMembershipChange.Rejoin) {
            return resendRejoin(change = change, group = group, client = client)
        }

        val roster = client.rosterOf(mlsGroupId = group.heldMlsGroupId())

        if (!roster.hasPendingCommit) {
            settled(change = change, group = group, client = client, roster = roster)

            return null
        }

        val answer = answerTo(change = change)

        // The user's own, refused for its epoch: kept, for the group sync to make it again.
        if (answer is GroupMembershipAnswer.KeyGenerationNotCurrent && change.isTheUsers()) {
            outdate(group = group, client = client)
        } else {
            actOn(answer = answer, change = change, group = group, client = client)
        }

        return answer
    }

    /**
     * Takes up [change], the rejoin pending for [group], from where it was left, see
     * [GroupMembershipSender], and returns what the server answered, or null when nothing needed
     * sending: the state it was built in deleted already, its refusal acted on but for the group
     * forgetting its MLS id, which is done now.
     */
    private suspend fun resendRejoin(
        change: PendingMembershipChange.Rejoin,
        group: GroupEntity,
        client: MlsClient,
    ): GroupMembershipAnswer? {
        if (!client.holdsGroup(mlsGroupId = group.heldMlsGroupId())) {
            forgetRejoin(group.uuid)

            return null
        }

        val answer = answerTo(change = change)

        actOn(answer = answer, change = change, group = group, client = client)

        return answer
    }

    /** The group [change] is pending for, which this device holds. */
    private suspend fun heldGroupOf(change: PendingMembershipChange): GroupEntity =
        checkNotNull(group(change.groupUuid)) { "A change is only pending for a group this device holds." }

    /**
     * Acts on [change], whose Commit is no longer pending in [group], [roster] as the group stands,
     * the change outliving it, see [GroupMembershipSender]: applied if the group is past the epoch
     * it was built in, and discarded otherwise.
     *
     * Throws [MlsException] when the key of the epoch the group is in could not be derived.
     */
    private suspend fun settled(
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
        roster: GroupRoster,
    ) {
        if (roster.epoch > change.commitEpoch) {
            applied(
                group = group,
                members = roster.members,
                key = client.epochKeyIn(mlsGroupId = group.heldMlsGroupId(), groupUuid = group.uuid),
            )
        } else {
            // Refused and discarded, the process not living to drop it.
            dropPending(change.groupUuid)
        }
    }

    /** Acts on the server holding the change pending for [group], see [GroupMembershipSender]. */
    private suspend fun taken(group: GroupEntity, client: MlsClient) {
        val applied = try {
            client.applyPendingCommitIn(mlsGroupId = group.heldMlsGroupId(), groupUuid = group.uuid)
        } catch (_: MlsException) {
            // Taken all the same: sent again, it is answered 409, and applied then.
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        applied(group = group, members = applied.members, key = applied.key)
    }

    /**
     * Acts on the server holding the rejoin pending for [group], see [GroupRejoiner]: its Commit
     * applied already, the group's members are those its tree holds, and the key of the epoch it is
     * in the group's, backed up with the copy the rejoin carried, see [applied]. The next sync is
     * booked, a minute on, by which the members' devices will have read the Commit and published the
     * link back from that key the user may have been unable to, see [RejoinGroupBody.previousKey],
     * which what was sealed in the epochs this device skipped is read through, see [GroupKeyChain].
     */
    private suspend fun rejoined(group: GroupEntity, client: MlsClient) {
        val mlsGroupId = group.heldMlsGroupId()

        val (roster, key) = try {
            client.rosterOf(mlsGroupId = mlsGroupId) to
                client.epochKeyIn(mlsGroupId = mlsGroupId, groupUuid = group.uuid)
        } catch (_: MlsException) {
            // Taken all the same: sent again, it is answered 409, and acted on then.
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        applied(group = group, members = roster.members, key = key)

        resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)
    }

    /**
     * Records the members of [group] once the Commit of the change pending for it is applied,
     * [members] as its tree holds them, the creator their admin, with the signature key each holds
     * there, see [GroupMemberSignatureKeyEntity], rebuilds who may wake the user with them, see
     * [WakeList], and takes [key], the key of the epoch it took the group to, as the group's, the
     * change dropped with it, see [GroupDao.takeCommittedKey]. The keys recorded for those a
     * removal took out stay; they themselves go from the wake list there and then, unless they are
     * contacts or in another of the user's groups.
     */
    private suspend fun applied(group: GroupEntity, members: List<MemberInfo>, key: EpochKey) {
        val rows = members.map {
            GroupMemberEntity(userUuid = it.userUuid, groupUuid = group.uuid, isAdmin = it.userUuid == group.ownerUuid)
        }

        replaceMembers(group.uuid, rows)

        recordSignatureKeys(members.signatureKeysIn(groupUuid = group.uuid))

        rebuildWakeList()

        takeCommittedKey(GroupKeyEntity(groupUuid = group.uuid, generation = key.epoch, groupKey = key.key))
    }

    /**
     * Keeps the change pending for [group], one the user asked for, to be made again, its Commit
     * refused for its epoch, see [PendingMembershipChangeEntity.isOutdated]: the Commit discarded
     * first, then the change recorded so, as for [discard].
     */
    private suspend fun outdate(group: GroupEntity, client: MlsClient) {
        try {
            client.discardPendingCommitIn(mlsGroupId = group.heldMlsGroupId())
        } catch (_: MlsException) {
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        markOutdated(group.uuid)
    }

    /** Forgets [change], the change pending for [group], refused by the server, see [discard] and [forget]. */
    private suspend fun refused(change: PendingMembershipChange, group: GroupEntity, client: MlsClient) {
        if (change is PendingMembershipChange.Rejoin) {
            forget(group = group, client = client)
        } else {
            discard(group = group, client = client)
        }
    }

    /**
     * Forgets the rejoin pending for [group], refused by the server, or of no use any more: the
     * state it was built in first, then the group's MLS id with the rejoin, see
     * [GroupDao.forgetRejoin], so that a forgetting cut short leaves a rejoin the next sync finds
     * the state of deleted, see [resendRejoin]. A state that could not be deleted leaves the rejoin
     * for the next sync, which hears the refusal again.
     */
    private suspend fun forget(group: GroupEntity, client: MlsClient) {
        try {
            client.deleteGroupOf(mlsGroupId = group.heldMlsGroupId())
        } catch (_: MlsException) {
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        forgetRejoin(group.uuid)
    }

    /**
     * Forgets the change pending for [group], refused by the server: its Commit first, then the
     * change, so that a discard cut short leaves a change the next sync finds discarded, see
     * [resend]. A Commit that could not be discarded leaves the change for the next sync, which
     * hears the refusal again.
     */
    private suspend fun discard(group: GroupEntity, client: MlsClient) {
        try {
            client.discardPendingCommitIn(mlsGroupId = group.heldMlsGroupId())
        } catch (_: MlsException) {
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        dropPending(group.uuid)
    }
}

/**
 * Whether the user asked for this change, rather than the group sync making it, see [GroupSync]: a
 * rejoin is the sync's, see [GroupRejoiner].
 */
private fun PendingMembershipChange.isTheUsers(): Boolean =
    this is PendingMembershipChange.Addition || this is PendingMembershipChange.Removal

/**
 * Whether this is a rejoin, and [answer], the server's to it, a `404`: a server that takes no
 * rejoin, which answers so on every route of theirs for as long as some phone it serves could not
 * read one, its minimum build lowered again say, or the proxy in front of it as it is redeployed,
 * see [GroupRejoiner.rejoinUnderGroupsLock]; or a user no longer in the group, whose rejoin the same
 * pass forgets, see [GroupMembershipSender.settleUnlisted].
 *
 * Not a send gone unanswered, then, which booking the next sync a minute on is for: sent again
 * every minute, it would be answered the same for as long as the server keeps its rejoins shut, and
 * hold back every change pending after it, see [GroupMembershipSender.resendPending]. It waits for
 * whatever syncs next, which takes it up from where it is: a server taking rejoins again answers it
 * as any, its nonce telling whether it took it already.
 */
private fun PendingMembershipChange.isRejoinNotFound(answer: GroupMembershipAnswer?): Boolean =
    this is PendingMembershipChange.Rejoin &&
        answer is GroupMembershipAnswer.Failed &&
        answer.error == NetworkError.NOT_FOUND

/** The MLS group of this group, which every group a change is pending for has, see [GroupMembershipChanger]. */
private fun GroupEntity.heldMlsGroupId(): ByteArray =
    checkNotNull(mlsGroupId) { "A change is only made to a group this device holds the MLS state of." }
