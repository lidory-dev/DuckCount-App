package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
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
import push.WakeList
import uniffi.lib_duckcount.MemberInfo
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
 *   group holds then, the signature key each holds in its tree recorded, see
 *   [GroupMemberSignatureKeyEntity], who may wake the user rebuilt with them, see [WakeList], and
 *   the change goes. An addition is done with then. A removal
 *   is followed by the rotation of the group's key, see [rotate], saved in its place and sent at
 *   once, whose being taken gives the group its new key, of the next generation, backed up already:
 *   the rotation carried the creator's copy of it, see [RotateGroupKeyBody.keyCopy]. The key it
 *   replaces is kept, see [GroupDao.adoptRotatedKey].
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
 * [addMembers], [removeMembers], [rotateKey], [listGroups] and [history] are those of [GroupApi],
 * [group] is [GroupDao.get], [listPending] is [GroupDao.getMembershipChanges], [savePending] is
 * [GroupDao.upsertMembershipChange], [dropPending] is [GroupDao.deleteMembershipChange],
 * [replaceMembers] is [GroupDao.replaceMembers], [recordSignatureKeys] is
 * [GroupDao.upsertSignatureKeys], [rebuildWakeList] is [WakeList.rebuild], [adoptKey] is
 * [GroupDao.adoptRotatedKey], [client] is [MlsIdentityRepository.client], [receiveAccountDeletions]
 * is [DirectMessageSender.receiveAccountDeletions] and [sealKeyCopy] is [GroupKeyCopies.seal], or
 * what a test stands in for any of them.
 */
class GroupMembershipSender internal constructor(
    private val addMembers: suspend (groupUuid: String, body: AddGroupMembersBody) -> GroupMembershipAnswer,
    private val removeMembers: suspend (groupUuid: String, body: RemoveGroupMembersBody) -> GroupMembershipAnswer,
    private val rotateKey: suspend (groupUuid: String, body: RotateGroupKeyBody) -> GroupMembershipAnswer,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val history: suspend (groupUuid: String) -> Result<GroupHistoryResponse, NetworkError>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val listPending: suspend () -> List<PendingMembershipChangeEntity>,
    private val savePending: suspend (change: PendingMembershipChangeEntity) -> Unit,
    private val dropPending: suspend (groupUuid: String) -> Unit,
    private val replaceMembers: suspend (groupUuid: String, members: List<GroupMemberEntity>) -> Unit,
    private val recordSignatureKeys: suspend (signatureKeys: List<GroupMemberSignatureKeyEntity>) -> Unit,
    private val rebuildWakeList: suspend () -> Unit,
    private val adoptKey: suspend (groupUuid: String, groupKey: ByteArray, keyGeneration: Long) -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val receiveAccountDeletions: suspend (userUuids: Collection<String>) -> Unit,
    private val sealKeyCopy: suspend (
        groupUuid: String,
        groupKey: ByteArray,
        keyGeneration: Long,
    ) -> Result<EncryptedFieldDto, VaultError>,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupKeyCopies: GroupKeyCopies,
        mlsIdentityRepository: MlsIdentityRepository,
        directMessageSender: DirectMessageSender,
        wakeList: WakeList,
        resendScheduler: ResendScheduler,
    ) : this(
        addMembers = groupApi::addMembers,
        removeMembers = groupApi::removeMembers,
        rotateKey = groupApi::rotateKey,
        listGroups = groupApi::getGroups,
        history = groupApi::history,
        group = groupDao::get,
        listPending = groupDao::getMembershipChanges,
        savePending = groupDao::upsertMembershipChange,
        dropPending = groupDao::deleteMembershipChange,
        replaceMembers = groupDao::replaceMembers,
        recordSignatureKeys = groupDao::upsertSignatureKeys,
        rebuildWakeList = wakeList::rebuild,
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
                applied(change = change, group = group, client = client, members = roster.members)

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
            adoptKey(change.groupUuid, change.groupKey, change.body.keyGeneration)

            return
        }

        val members = try {
            client.applyPendingCommitIn(mlsGroupId = group.heldMlsGroupId())
        } catch (_: MlsException) {
            // Taken all the same: sent again, it is answered 409, and applied then.
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        applied(change = change, group = group, client = client, members = members)
    }

    /**
     * Records the members of [group] once the Commit of [change] is applied, [members] as its tree
     * holds them, the creator their admin, with the signature key each holds there, see
     * [GroupMemberSignatureKeyEntity], rebuilds who may wake the user with them, see [WakeList],
     * and then drops an addition, or rotates the group's key after a removal, see [rotate]. The
     * keys recorded for those a removal took out stay; they themselves go from the wake list there
     * and then, unless they are contacts or in another of the user's groups.
     */
    private suspend fun applied(
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
        members: List<MemberInfo>,
    ) {
        val rows = members.map {
            GroupMemberEntity(userUuid = it.userUuid, groupUuid = group.uuid, isAdmin = it.userUuid == group.ownerUuid)
        }

        replaceMembers(group.uuid, rows)

        recordSignatureKeys(members.signatureKeysIn(groupUuid = group.uuid))

        rebuildWakeList()

        when (change) {
            is PendingMembershipChange.Removal -> rotate(group = group, client = client)
            else -> dropPending(change.groupUuid)
        }
    }

    /**
     * Rotates the key of [group], whose removal was just applied: a new key, the generation after
     * the one the group holds, see [GroupEntity.keyGeneration], handed over in an MLS application
     * message the members it removed cannot read, sent in the epoch the removal took the group to,
     * see [groupKeyMessageOf], and the group's information sealed under it in place of what the
     * removed members can open. Saved in place of the removal before it is sent, see [send].
     *
     * The key it replaces goes with it, sealed under the new one as the link at its generation of
     * the chain back through the group's keys, see [sealKeyLink] and [RotateGroupKeyBody.previousKey]:
     * nothing else sealed under the old key is sealed again, the group's expenses staying under the
     * generation each was written in, and a member added after the rotation, handed the new key
     * alone, gets the old one back through the chain, see [GroupKeyChain]. The members it removed
     * never get the new key, nor so the link, nor anything sealed under either.
     *
     * The version sealed again is the one the server holds, opened with the key it is sealed under,
     * and signed again as the revision it is, so that the rotation changes nothing of it, as the
     * server takes it, keeping its revision, see [RotateGroupKeyBody]: an edit of the user's whose
     * answer was lost, which this device has yet to show, see [GroupSync], is the version the
     * server holds. Only one the user signed, though: every member holds the key, and can seal
     * fields of their own, which the server could list, and signed again here they would be the
     * user's word to every member. Nor one older than the version this device shows, see
     * [GroupEntity.infoRevision], which the user signed once and an edit of theirs replaced since:
     * sealed under the new key, it is what a member holding that key alone, one added after the
     * rotation say, would be shown, the group gone back to a version it left. One that does not
     * open, that the user did not sign, or that is older, is replaced by the version this device
     * shows, as when the group was created.
     *
     * The versions the group's history holds from before it are sealed again under the new key as
     * well, see [RotateGroupKeyBody.previous], read from the server at the same point, as this
     * device keeps none of them: a member added after the rotation, or added back, holds the new
     * key alone, and reads the history with it. Each as it is, signature and all, at its revision,
     * so that the rotation changes nothing of it either, and only those the user signed that open
     * under the key it replaces, see [openSigned]: one that does not, a member's the server would
     * have the user vouch for, is left out, and the server drops it as it takes the rotation.
     *
     * The creator's copy of the new key goes with it, see [RotateGroupKeyBody.keyCopy], sealed
     * before anything is built in the group, see [keyCopyOf].
     *
     * Not done when the server cannot be asked for the version it holds or for its history, the
     * key ring cannot be read for the copy, or the user's storage fails: the removal stays, the
     * group past its epoch, which the next sync takes for a removal applied, and rotates the key
     * then. A key message the library refuses for good is a rotation that never can be, and the
     * removal goes, the group keeping the key it had.
     */
    private suspend fun rotate(group: GroupEntity, client: MlsClient) {
        val serverVersion = when (val listed = listGroups()) {
            is Result.Success -> listed.data
                .firstOrNull { it.uuid == group.uuid }
                ?.sealedVersion()
                ?.unseal(groupKey = group.groupKey, groupUuid = group.uuid)
                ?.takeIf {
                    it.revision >= group.infoRevision &&
                        it.isSignedBy(
                            ownerUuid = group.ownerUuid,
                            ownerSignatureKey = client.signaturePublicKey(),
                            groupUuid = group.uuid,
                        )
                }

            is Result.Error -> {
                resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

                return
            }
        }

        val previousVersions = when (val read = history(group.uuid)) {
            is Result.Success -> read.data.previous.mapNotNull {
                it.openSigned(group = group, creatorKey = client.signaturePublicKey())
            }

            is Result.Error -> {
                resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

                return
            }
        }

        val fields = serverVersion?.fields
            ?: GroupFields(title = group.title, description = group.description, currency = group.currency)
        val revision = serverVersion?.revision ?: group.infoRevision
        val groupKey = generateSymmetricEncryptionKey()
        val keyGeneration = group.keyGeneration + 1

        val previousKey = checkNotNull(
            sealKeyLink(
                previousKey = group.groupKey,
                groupKey = groupKey,
                groupUuid = group.uuid,
                generation = keyGeneration,
            ),
        ) { "A key from generateSymmetricEncryptionKey seals." }

        val sealedVersion = checkNotNull(
            fields.signedAs(client = client, groupUuid = group.uuid, revision = revision)
                ?.seal(groupKey = groupKey, groupUuid = group.uuid),
        ) { "The library signs every version, and a key from generateSymmetricEncryptionKey seals." }

        val previous = previousVersions.map { version ->
            val sealed = checkNotNull(version.seal(groupKey = groupKey, groupUuid = group.uuid)) {
                "A key from generateSymmetricEncryptionKey seals."
            }

            PreviousGroupVersionBody(
                revision = sealed.revision,
                title = sealed.fields.title,
                description = sealed.fields.description,
                signature = sealed.signature,
            )
        }

        val keyCopy = keyCopyOf(groupUuid = group.uuid, groupKey = groupKey, keyGeneration = keyGeneration) ?: run {
            resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

            return
        }

        val keyMessage = try {
            client.encryptApplicationMessage(
                mlsGroupId = group.heldMlsGroupId(),
                payload = groupKeyMessageOf(groupKey = groupKey, keyGeneration = keyGeneration),
            )
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
                title = sealedVersion.fields.title,
                description = sealedVersion.fields.description,
                currency = sealedVersion.fields.currency,
                signature = sealedVersion.signature,
                keyCopy = keyCopy,
                previous = previous,
                keyGeneration = keyGeneration,
                previousKey = previousKey,
            ),
            groupKey = groupKey,
        )

        // In place of the removal, which it ends: from here on, the group past the removal's epoch
        // is no longer a removal whose key is still to be rotated.
        savePending(rotation.toEntity())

        send(change = rotation, group = group, client = client)
    }

    /**
     * The creator's copy of [groupKey] as the new key of the group [groupUuid], the generation
     * [keyGeneration] of its keys, see [GroupKeyCopies.seal], or null when the key ring cannot be
     * read: a locked device's included, whose reads of it throw rather than fail. The removal the
     * server took is made all the same, its rotation left to the next sync, where the throw would
     * crash the removal the user asked for, or stop the sync's pass.
     */
    private suspend fun keyCopyOf(groupUuid: String, groupKey: ByteArray, keyGeneration: Long): EncryptedFieldDto? =
        try {
            (sealKeyCopy(groupUuid, groupKey, keyGeneration) as? Result.Success)?.data
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
