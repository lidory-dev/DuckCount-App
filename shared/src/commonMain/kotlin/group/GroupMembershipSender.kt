package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.CancellationException
import message.DirectMessageSender
import message.ResendScheduler
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.applyPendingCommitIn
import mls.discardPendingCommitIn
import mls.encryptApplicationMessage
import mls.isRetryable
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultError
import kotlin.io.encoding.Base64

/**
 * How soon a change the server was not heard to answer, or that this device could not act on
 * once taken, is sent again: as soon as a creation is, see [GroupCreationSender], the members
 * waiting on it as the invitees of one do.
 */
private const val UNANSWERED_RESEND_SECONDS = 60L

/**
 * Sends a change to the members of a group the user created, and acts on what the server
 * answers: the one place that decides what becomes of one, whether it is the first send, see
 * [GroupMembershipChanger], or a resend at a later sync, see [resendPending].
 *
 * The change was saved before the first send, see [PendingMembershipChangeEntity], its Commit
 * pending in the library, so that whatever the answer, or none, the device holds what the server
 * may hold:
 * - The server holds the change, now or from an earlier send, see [GroupMembershipAnswer.Made]
 *   and [GroupMembershipAnswer.AlreadyMade]: the Commit is applied, the members are those the
 *   group holds then, and the change goes. An addition is done with then. A removal is followed by
 *   the rotation of the group's key, see [rotate], saved in its place and sent at once, whose being
 *   taken gives the group its new key, backed up already: the rotation carried the creator's copy
 *   of it, see [RotateGroupKeyBody.keyCopy].
 * - The server refused it, writing nothing, see [GroupMembershipAnswer.DeletedMembers] and
 *   [GroupMembershipAnswer.Refused]: the Commit is discarded and the change goes, the group as it
 *   was. Members named as deleted are acted on as any other answer naming them is, see
 *   [DirectMessageSender.receiveAccountDeletions]. A key rotation refused leaves the group with
 *   the key it had.
 * - The server has not taken it yet, see [GroupMembershipAnswer.RateLimited] and
 *   [GroupMembershipAnswer.Failed]: everything stays, and the next sync is booked for when it may
 *   go through, see [ResendScheduler].
 *
 * Every step can be cut short, the process killed, and taken up again by the next sync from where
 * it stopped: a Commit still pending is sent again, the server answering `409` for one it took; one
 * no longer pending, the change outliving it, was applied if the group is past the epoch it was
 * built in, see [PendingMembershipChangeEntity.commitEpoch], and discarded otherwise; and a key
 * rotation is sent again until it is taken, the key going to the group in the same transaction
 * that drops it, see [GroupDao.adoptRotatedKey].
 *
 * To be called under [withGroupsLock], which it does not take itself, as for a creation.
 *
 * [addMembers], [removeMembers], [rotateKey] and [listGroups] are those of [GroupApi], [group] is
 * [GroupDao.get], [listPending] is [GroupDao.getMembershipChanges], [savePending] is
 * [GroupDao.upsertMembershipChange], [dropPending] is [GroupDao.deleteMembershipChange],
 * [replaceMembers] is [GroupDao.replaceMembers], [adoptKey] is [GroupDao.adoptRotatedKey], [client]
 * is [MlsIdentityRepository.client], [receiveAccountDeletions] is
 * [DirectMessageSender.receiveAccountDeletions] and [sealKeyCopy] is [GroupKeyCopies.seal], or what
 * a test stands in for any of them.
 */
class GroupMembershipSender internal constructor(
    private val addMembers: suspend (groupUuid: String, body: AddGroupMembersBody) -> GroupMembershipAnswer,
    private val removeMembers: suspend (groupUuid: String, body: RemoveGroupMembersBody) -> GroupMembershipAnswer,
    private val rotateKey: suspend (groupUuid: String, body: RotateGroupKeyBody) -> GroupMembershipAnswer,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val listPending: suspend () -> List<PendingMembershipChangeEntity>,
    private val savePending: suspend (change: PendingMembershipChangeEntity) -> Unit,
    private val dropPending: suspend (groupUuid: String) -> Unit,
    private val replaceMembers: suspend (groupUuid: String, members: List<GroupMemberEntity>) -> Unit,
    private val adoptKey: suspend (groupUuid: String, groupKey: ByteArray) -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val receiveAccountDeletions: suspend (userUuids: Collection<String>) -> Unit,
    private val sealKeyCopy: suspend (groupUuid: String, groupKey: ByteArray) -> Result<EncryptedFieldDto, VaultError>,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupKeyCopies: GroupKeyCopies,
        mlsIdentityRepository: MlsIdentityRepository,
        directMessageSender: DirectMessageSender,
        resendScheduler: ResendScheduler,
    ) : this(
        addMembers = groupApi::addMembers,
        removeMembers = groupApi::removeMembers,
        rotateKey = groupApi::rotateKey,
        listGroups = groupApi::getGroups,
        group = groupDao::get,
        listPending = groupDao::getMembershipChanges,
        savePending = groupDao::upsertMembershipChange,
        dropPending = groupDao::deleteMembershipChange,
        replaceMembers = groupDao::replaceMembers,
        adoptKey = groupDao::adoptRotatedKey,
        client = mlsIdentityRepository::client,
        receiveAccountDeletions = directMessageSender::receiveAccountDeletions,
        sealKeyCopy = groupKeyCopies::seal,
        resendScheduler = resendScheduler,
    )

    /**
     * Sends [change] to the group [group], whose MLS state [client] holds, its Commit pending if it
     * has one, and acts on the answer, see [GroupMembershipSender], which it returns. For a
     * removal, that of the removal: the key rotation after it is acted on all the same.
     */
    internal suspend fun send(
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ): GroupMembershipAnswer {
        val answer = when (change) {
            is PendingMembershipChange.Addition -> addMembers(change.groupUuid, change.body)
            is PendingMembershipChange.Removal -> removeMembers(change.groupUuid, change.body)
            is PendingMembershipChange.KeyRotation -> rotateKey(change.groupUuid, change.body)
        }

        when (answer) {
            GroupMembershipAnswer.Made, GroupMembershipAnswer.AlreadyMade -> taken(change, group, client)

            is GroupMembershipAnswer.DeletedMembers -> {
                discard(change = change, group = group, client = client)
                receiveAccountDeletions(answer.userUuids)
            }

            is GroupMembershipAnswer.Refused -> discard(change = change, group = group, client = client)

            is GroupMembershipAnswer.RateLimited -> resendScheduler.resendAfter(seconds = answer.retryAfterSeconds)

            is GroupMembershipAnswer.Failed -> resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)
        }

        return answer
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
     * or refused for the limit, which the three kinds share: the next one would fare no better, and
     * the resend booked for it goes for them all.
     *
     * Fails with what kept the first one from going through, the limit aside, which is no failure:
     * nothing needs doing but waiting for the resend.
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

        for (change in pending.map { it.toChange() }) {
            val answer = try {
                resend(change = change, client = client)
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage

                continue
            }

            when (answer) {
                is GroupMembershipAnswer.Failed -> return Result.Error(failure ?: GroupSyncError.Network(answer.error))

                is GroupMembershipAnswer.RateLimited -> break

                else -> Unit
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
        val group = checkNotNull(group(change.groupUuid)) { "A change is only pending for a group this device holds." }

        val commitEpoch = when (change) {
            is PendingMembershipChange.Addition -> change.commitEpoch
            is PendingMembershipChange.Removal -> change.commitEpoch
            is PendingMembershipChange.KeyRotation -> return send(change = change, group = group, client = client)
        }

        val roster = client.rosterOf(mlsGroupId = group.heldMlsGroupId())

        return when {
            roster.hasPendingCommit -> send(change = change, group = group, client = client)

            roster.epoch > commitEpoch -> {
                applied(change = change, group = group, client = client, memberUuids = roster.memberUuids)

                null
            }

            // Refused and discarded, the process not living to drop it.
            else -> {
                dropPending(change.groupUuid)

                null
            }
        }
    }

    /** Acts on the server holding [change], see [GroupMembershipSender]. */
    private suspend fun taken(change: PendingMembershipChange, group: GroupEntity, client: MlsClient) {
        if (change is PendingMembershipChange.KeyRotation) {
            adoptKey(change.groupUuid, change.groupKey)

            return
        }

        val memberUuids = try {
            client.applyPendingCommitIn(mlsGroupId = group.heldMlsGroupId())
        } catch (_: MlsException) {
            // Taken all the same: sent again, it is answered 409, and applied then.
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        applied(change = change, group = group, client = client, memberUuids = memberUuids)
    }

    /**
     * Records the members of [group] once the Commit of [change] is applied, [memberUuids], the
     * creator their admin, and then drops an addition, or rotates the group's key after a removal,
     * see [rotate].
     */
    private suspend fun applied(
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
        memberUuids: List<String>,
    ) {
        val members = memberUuids.map {
            GroupMemberEntity(userUuid = it, groupUuid = group.uuid, isAdmin = it == group.ownerUuid)
        }

        replaceMembers(group.uuid, members)

        when (change) {
            is PendingMembershipChange.Removal -> rotate(group = group, client = client)
            else -> dropPending(change.groupUuid)
        }
    }

    /**
     * Rotates the key of [group], whose removal was just applied: a new key, handed over in an MLS
     * application message the members it removed cannot read, sent in the epoch the removal took
     * the group to, see [groupKeyMessageOf], and the group's fields sealed under it in place of
     * those the removed members can open. Saved in place of the removal before it is sent, see
     * [send].
     *
     * The fields re-sealed are those the server holds, opened with the key they are sealed under,
     * so that the rotation changes nothing of them, as the server takes it, see
     * [RotateGroupKeyBody]: what the user edited of them is edited on this device alone. Those it
     * holds not opening, the user's own are sealed instead, as when the group was created.
     *
     * The creator's copy of the new key goes with it, see [RotateGroupKeyBody.keyCopy], sealed
     * before anything is built in the group, see [keyCopyOf].
     *
     * Not done when the server cannot be asked for the fields, the key ring cannot be read for the
     * copy, or the user's storage fails: the removal stays, the group past its epoch, which the next
     * sync takes for a removal applied, and rotates the key then. A key message the library refuses
     * for good is a rotation that never can be, and the removal goes, the group keeping the key it
     * had.
     */
    private suspend fun rotate(group: GroupEntity, client: MlsClient) {
        val opened = when (val listed = listGroups()) {
            is Result.Success -> listed.data
                .firstOrNull { it.uuid == group.uuid }
                ?.let { SealedGroupFields(title = it.title, description = it.description, currency = it.currency) }
                ?.unseal(groupKey = group.groupKey, groupUuid = group.uuid)

            is Result.Error -> {
                resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

                return
            }
        }

        val fields = opened
            ?: GroupFields(title = group.title, description = group.description, currency = group.currency)
        val groupKey = generateSymmetricEncryptionKey()

        val sealedFields = checkNotNull(fields.seal(groupKey = groupKey, groupUuid = group.uuid)) {
            "A key from generateSymmetricEncryptionKey seals."
        }

        val keyCopy = keyCopyOf(groupUuid = group.uuid, groupKey = groupKey) ?: run {
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        val keyMessage = try {
            client.encryptApplicationMessage(mlsGroupId = group.heldMlsGroupId(), payload = groupKeyMessageOf(groupKey))
        } catch (exception: MlsException) {
            if (exception.isRetryable()) {
                resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)
            } else {
                dropPending(group.uuid)
            }

            return
        }

        val rotation = PendingMembershipChange.KeyRotation(
            groupUuid = group.uuid,
            body = RotateGroupKeyBody(
                nonce = newChangeNonce(),
                keyMessage = Base64.Default.encode(keyMessage),
                title = sealedFields.title,
                description = sealedFields.description,
                currency = sealedFields.currency,
                keyCopy = keyCopy,
            ),
            groupKey = groupKey,
        )

        // In place of the removal, which it ends: from here on, the group past the removal's epoch
        // is no longer a removal whose key is still to be rotated.
        savePending(rotation.toEntity())

        send(change = rotation, group = group, client = client)
    }

    /**
     * The creator's copy of [groupKey] as the new key of the group [groupUuid], see
     * [GroupKeyCopies.seal], or null when the key ring cannot be read: a locked device's included,
     * whose reads of it throw rather than fail. The removal the server took is made all the same,
     * its rotation left to the next sync, where the throw would crash the removal the user asked
     * for, or stop the sync's pass.
     */
    private suspend fun keyCopyOf(groupUuid: String, groupKey: ByteArray): EncryptedFieldDto? =
        try {
            (sealKeyCopy(groupUuid, groupKey) as? Result.Success)?.data
        } catch (exception: CancellationException) {
            // The caller going away, which the catch below would otherwise take for a locked
            // device, a CancellationException being an IllegalStateException.
            throw exception
        } catch (_: IllegalStateException) {
            null
        }

    /**
     * Forgets [change], refused by the server: its Commit first, then the change, so that a
     * discard cut short leaves a change the next sync finds discarded, see [resend]. A Commit that
     * could not be discarded leaves the change for the next sync, which hears the refusal again.
     */
    private suspend fun discard(change: PendingMembershipChange, group: GroupEntity, client: MlsClient) {
        if (change !is PendingMembershipChange.KeyRotation) {
            try {
                client.discardPendingCommitIn(mlsGroupId = group.heldMlsGroupId())
            } catch (_: MlsException) {
                resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

                return
            }
        }

        dropPending(change.groupUuid)
    }
}

/** The MLS group of this group, which every group a change is pending for has, see [GroupMembershipChanger]. */
private fun GroupEntity.heldMlsGroupId(): ByteArray =
    checkNotNull(mlsGroupId) { "A change is only made to a group this device holds the MLS state of." }
