package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.PendingGroupCreationEntity
import kotlinx.serialization.json.Json
import message.DirectMessageSender
import message.ResendScheduler
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import uniffi.lib_duckcount.MlsClient
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * How soon a creation the server was not heard to answer is sent again: soon enough that its
 * members are not kept waiting long for a group that may be theirs already, and no sooner, each
 * resend being a whole sync and up to some 370 KB of Welcome.
 */
private const val UNANSWERED_RESEND_SECONDS = 60L

/**
 * Sends a group the user created to the server, and acts on what it answers: the one place that
 * decides what becomes of a creation, whether it is the first send, see [GroupCreator], or a
 * resend at a later sync, see [resendPending].
 *
 * The group and its pending creation were saved before the first send, see
 * [GroupDao.insertCreated], so that whatever the answer, or none, the device holds what the
 * server may hold:
 * - The server holds the group, now or from an earlier send, see [GroupCreationAnswer.Created] and
 *   [GroupCreationAnswer.AlreadyExists]: the pending creation goes, and the group stays, backed up,
 *   the creation having carried the creator's copy of its key, see [CreateGroupBody.keyCopy].
 * - The server refused it, writing nothing, see [GroupCreationAnswer.DeletedInvitees] and
 *   [GroupCreationAnswer.Refused]: the group goes, with its members, its pending creation and the
 *   MLS group, which nobody else will ever join. Contacts named as deleted are acted on as any
 *   other answer naming them is, see [DirectMessageSender.receiveAccountDeletions].
 * - The server has not taken it yet, see [GroupCreationAnswer.RateLimited] and
 *   [GroupCreationAnswer.Failed]: everything stays, and the next sync is booked for when it may
 *   go through, see [ResendScheduler]. Whatever runs first sends it again.
 *
 * To be called under [withGroupsLock], which it does not take itself: the creation sends while
 * holding it already.
 *
 * [post] is [GroupApi.createGroup], [dropTaken] is [GroupDao.deleteTakenCreation],
 * [deleteRefused] is [GroupDao.deleteRefusedCreation], [listPending] is
 * [GroupDao.getPendingCreations], [client] is [MlsIdentityRepository.client],
 * [receiveAccountDeletions] is [DirectMessageSender.receiveAccountDeletions], or what a test
 * stands in for any of them.
 */
class GroupCreationSender internal constructor(
    private val post: suspend (body: CreateGroupBody) -> GroupCreationAnswer,
    private val dropTaken: suspend (groupUuid: String) -> Unit,
    private val deleteRefused: suspend (groupUuid: String) -> GroupEntity?,
    private val listPending: suspend () -> List<PendingGroupCreationEntity>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val receiveAccountDeletions: suspend (userUuids: Collection<String>) -> Unit,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        directMessageSender: DirectMessageSender,
        resendScheduler: ResendScheduler,
    ) : this(
        post = groupApi::createGroup,
        dropTaken = groupDao::deleteTakenCreation,
        deleteRefused = groupDao::deleteRefusedCreation,
        listPending = groupDao::getPendingCreations,
        client = mlsIdentityRepository::client,
        receiveAccountDeletions = directMessageSender::receiveAccountDeletions,
        resendScheduler = resendScheduler,
    )

    /**
     * Sends [pendingCreation] and acts on the answer, see [GroupCreationSender], which it returns.
     */
    suspend fun send(pendingCreation: PendingGroupCreationEntity): GroupCreationAnswer {
        val answer = post(pendingCreation.toBody())

        when (answer) {
            GroupCreationAnswer.Created, GroupCreationAnswer.AlreadyExists ->
                dropTaken(pendingCreation.groupUuid)

            is GroupCreationAnswer.DeletedInvitees -> {
                discard(groupUuid = pendingCreation.groupUuid)
                receiveAccountDeletions(answer.userUuids)
            }

            is GroupCreationAnswer.Refused -> discard(groupUuid = pendingCreation.groupUuid)

            is GroupCreationAnswer.RateLimited -> resendScheduler.resendAfter(seconds = answer.retryAfterSeconds)

            is GroupCreationAnswer.Failed -> resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)
        }

        return answer
    }

    /**
     * Books the resend of a creation about to go out for the first time, see [GroupCreator]: one
     * whose answer the process does not live to hear, killed or cut short while it uploads, would
     * otherwise wait for whatever syncs next, a push or the group list opening. One the server
     * takes meanwhile leaves that resend a sync with nothing to send.
     */
    suspend fun bookResend() = resendScheduler.resendAfter(seconds = UNANSWERED_RESEND_SECONDS)

    /**
     * Sends again every creation the server has not been heard to take, see [send], and stops at
     * the first it could not get an answer to, or was refused for the limit: the next one would
     * fare no better, and the resend booked for it goes for them all.
     *
     * Fails with what kept that one from being answered, the limit aside, which is no failure:
     * nothing needs doing but waiting for the resend.
     */
    suspend fun resendPending(): EmptyResult<NetworkError> {
        listPending().forEach { pendingCreation ->
            when (val answer = send(pendingCreation = pendingCreation)) {
                is GroupCreationAnswer.Failed -> return Result.Error(answer.error)
                is GroupCreationAnswer.RateLimited -> return Result.Success(Unit)
                else -> Unit
            }
        }

        return Result.Success(Unit)
    }

    /**
     * Forgets the group [groupUuid], refused by the server: its rows first, then its MLS group, so
     * that a deletion that fails leaves a group nothing names, never a name for a group that is
     * gone. The server stores nothing of a creation it refuses, the creator's copy of the group's
     * key included, so there is no copy to drop.
     */
    private suspend fun discard(groupUuid: String) {
        val group = deleteRefused(groupUuid) ?: return

        val mlsGroupId = group.mlsGroupId ?: return

        // A client that cannot be opened leaves the group behind, which nobody can reach: its
        // Welcome never reached the server.
        val opened = client() as? Result.Success ?: return

        opened.data.deleteGroupQuietly(mlsGroupId = mlsGroupId)
    }
}

/**
 * What goes out as `POST /groups` for [body], creating the group [groupUuid] its nonce derives, to
 * keep until the server answers it.
 */
internal fun pendingCreationOf(groupUuid: String, body: CreateGroupBody) = PendingGroupCreationEntity(
    groupUuid = groupUuid,
    body = Json.encodeToString(CreateGroupBody.serializer(), body),
)

/** The body [pendingCreationOf] kept. */
internal fun PendingGroupCreationEntity.toBody(): CreateGroupBody =
    Json.decodeFromString(CreateGroupBody.serializer(), body)
