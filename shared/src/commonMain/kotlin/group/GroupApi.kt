package group

import database.data.remote.EncryptedFieldDto
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import networking.CURSOR_QUERY
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.LARGE_BODY_TIMEOUT_MILLIS
import networking.ListingPage
import networking.readWholeListing
import networking.retryAfterSeconds
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import kotlin.io.encoding.Base64

/**
 * The most message UUIDs one acknowledgement in a group may carry. The server refuses a larger
 * batch with a `400`.
 */
const val MAX_GROUP_ACKNOWLEDGEMENT_BATCH = 100

/**
 * The plain text of the `409` the owner's replacement of a Commit hears when the server records no
 * Commit to replace, see [GroupApi.replaceCommit], told apart by it from the `409` of a nonce sent
 * already.
 */
private const val NOTHING_TO_REPLACE = "Nothing to replace."

/** How the JSON `409`s of the routes holding a write to the group's epoch are read. */
private val conflictJson = Json { ignoreUnknownKeys = true }

/**
 * Remote data source for the caller's groups: creating one, listing them, editing one and reading
 * the history of its edits, changing the members of one, leaving one, the caller's own copy of
 * each one's key, and the messages waiting in each, the Welcome that lets an invitee in among them.
 *
 * Every field of a group is sealed on the creator's device under a key the server never holds,
 * see [GroupFields.seal], one the group's MLS epoch gives every member, see [GroupKeyChain], and
 * signed by them, see [GroupVersion], each member's copy of that key under a key only they hold,
 * see [GroupKeyCopies], and every message is MLS, so the server learns who is in which group and
 * nothing of what they share.
 */
class GroupApi(
    private val client: DuckCountClient,
) {
    /**
     * Creates the group whose UUID [CreateGroupBody.nonce] derives from the caller's, the caller
     * owning it, and hands its Welcome to every invitee, see [GroupCreationAnswer] for what the
     * server can answer. Given [LARGE_BODY_TIMEOUT_MILLIS] for the Welcome, which carries the
     * group's whole ratchet tree, some 370 KB at a hundred members: a creation that runs out of
     * time is sent again all the same, see [GroupCreationAnswer.Failed].
     */
    suspend fun createGroup(body: CreateGroupBody): GroupCreationAnswer {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: GroupCreationAnswer? = null

        val sent = client.execute<Unit> {
            client.httpClient.post(urlString = DuckCountRoutes.Group.GROUPS) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }.also { response ->
                refusal = creationRefusalOf(status = response.status, headers = response.headers) { response.body() }
            }
        }

        return when (sent) {
            is Result.Success -> GroupCreationAnswer.Created
            is Result.Error -> refusal ?: creationAnswerOf(error = sent.error)
        }
    }

    /**
     * The groups the caller is a member of, those they were invited to and have not joined yet
     * included, and none they left, see [leave].
     */
    suspend fun getGroups(): Result<List<GroupDto>, NetworkError> =
        client.get<List<GroupDto>>(
            route = DuckCountRoutes.Group.GROUPS,
        )

    /**
     * Replaces the information of the group [groupUuid], which the caller owns, with the version
     * [body] carries, see [GroupEditor], and keeps the one it replaces in the group's history, see
     * [history], with the generation each is sealed under. The server hands it to no member: each
     * of their devices shows it once its next sync lists it, which the server wakes every other
     * current member for, see [push.PushSignal.Kind.GROUP_CHANGED]. See [GroupEditAnswer] for what
     * the server can answer.
     */
    suspend fun edit(groupUuid: String, body: EditGroupBody): GroupEditAnswer {
        // Read off the response as it comes back, as for a creation.
        var refusal: GroupEditAnswer? = null

        val sent = client.execute<Unit> {
            client.httpClient.put(urlString = DuckCountRoutes.Group.GROUP.replace(":group_uuid", groupUuid)) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }.also { response ->
                refusal = editRefusalOf(status = response.status) { response.bodyAsText() }
            }
        }

        return when (sent) {
            is Result.Success -> GroupEditAnswer.Edited
            is Result.Error -> refusal ?: GroupEditAnswer.Failed(error = sent.error)
        }
    }

    /**
     * The versions of the information of the group [groupUuid] the server keeps, see
     * [GroupHistoryResponse]: the current one and the last nine its edits replaced, the oldest
     * dropped as an edit comes in past them, see [GroupHistory]. The same for every member,
     * those added since included, who read each version with the key of the generation it names,
     * which the chain leads back to from the key they hold, see [GroupKeyChain].
     *
     * A `404` is a caller who is no current member of the group, or the proxy in front of the
     * server answering as it is redeployed.
     */
    suspend fun history(groupUuid: String): Result<GroupHistoryResponse, NetworkError> =
        client.get<GroupHistoryResponse>(
            route = DuckCountRoutes.Group.HISTORY,
            pathParams = mapOf("group_uuid" to groupUuid),
        )

    /**
     * Every message waiting for the caller in the group [groupUuid], oldest first, read a page at a
     * time, see [getGroupMessagesPage], or the first failure and none of them, see
     * [readWholeListing]. Each stays until acknowledged, see [acknowledgeGroupMessages].
     *
     * The server checks no membership, it lists by recipient: a group the caller is not a member
     * of, like one with nothing waiting for them, answers an empty list.
     */
    suspend fun getGroupMessages(groupUuid: String): Result<ListGroupMessagesResponse, NetworkError> =
        readWholeListing { cursor ->
            getGroupMessagesPage(groupUuid = groupUuid, cursor = cursor).map { page ->
                ListingPage(items = page.messages, nextCursor = page.nextCursor)
            }
        }.map { messages -> ListGroupMessagesResponse(messages = messages) }

    /**
     * The page of the messages waiting for the caller in the group [groupUuid] that [cursor], the
     * `next_cursor` of the page before, starts, or the first page with no cursor: at most a hundred
     * messages and 1 MiB of them, one at least, a Welcome or a Commit of a group of a hundred among
     * them, which is why each page gets [LARGE_BODY_TIMEOUT_MILLIS].
     */
    suspend fun getGroupMessagesPage(
        groupUuid: String,
        cursor: String?,
    ): Result<ListGroupMessagesResponse, NetworkError> =
        client.execute {
            client.httpClient.get(urlString = DuckCountRoutes.Group.MESSAGES.replace(":group_uuid", groupUuid)) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                cursor?.let { parameter(key = CURSOR_QUERY, value = it) }
            }
        }

    /**
     * Adds the members [body] names to the group [groupUuid], which the caller created, see
     * [GroupMembershipAnswer] for what the server can answer. Given as long as a creation, for the
     * Welcome it carries and the Commit, which carries every key package it adds, some 440 KB for
     * ninety-nine of them.
     */
    suspend fun addMembers(groupUuid: String, body: AddGroupMembersBody): GroupMembershipAnswer =
        changeMembers {
            client.httpClient.post(urlString = DuckCountRoutes.Group.MEMBERS.replace(":group_uuid", groupUuid)) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Removes the members [body] names from the group [groupUuid], which the caller created, see
     * [GroupMembershipAnswer]. Given as long as a creation too: the Commit encrypts a path to every
     * member staying, some 175 KB in a group of a hundred.
     */
    suspend fun removeMembers(groupUuid: String, body: RemoveGroupMembersBody): GroupMembershipAnswer =
        changeMembers {
            client.httpClient.delete(urlString = DuckCountRoutes.Group.MEMBERS.replace(":group_uuid", groupUuid)) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Removes the members [body] names, who departed from the group [groupUuid] and whom no Commit
     * removed yet, see [GroupDto.departed], which any current member may do, see
     * [GroupMembershipAnswer]. Given as long as a removal.
     */
    suspend fun removeDeparted(groupUuid: String, body: RemoveDepartedMembersBody): GroupMembershipAnswer =
        changeMembers {
            client.httpClient.delete(urlString = DuckCountRoutes.Group.DEPARTED.replace(":group_uuid", groupUuid)) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Replaces the Commit stored in the group [groupUuid] for [ReplaceGroupCommitBody.replacesEpoch]
     * that every member's device refused, and every one stored after it, with the one [body]
     * carries, which the owner alone may do, see [GroupMembershipAnswer]. Given as long as a
     * removal.
     */
    suspend fun replaceCommit(groupUuid: String, body: ReplaceGroupCommitBody): GroupMembershipAnswer =
        changeMembers {
            client.httpClient.post(
                urlString = DuckCountRoutes.Group.COMMIT_REPLACEMENT.replace(":group_uuid", groupUuid),
            ) {
                timeout { requestTimeoutMillis = LARGE_BODY_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Stores [keyCopy] as the caller's own copy of the key of the group [groupUuid], the generation
     * [keyGeneration] of its keys, in place of any they had, see [GroupKeyCopies.putUp]. A `404`
     * means the caller is no current member of the group, removed since it was listed say, or the
     * proxy in front of the server answering as it is redeployed: either way the next listing
     * decides again.
     */
    suspend fun saveKeyCopy(
        groupUuid: String,
        keyCopy: EncryptedFieldDto,
        keyGeneration: Long,
    ): EmptyResult<NetworkError> =
        client.put<Unit, SaveGroupKeyCopyBody>(
            route = DuckCountRoutes.Group.KEY_COPY,
            pathParams = mapOf("group_uuid" to groupUuid),
            body = SaveGroupKeyCopyBody(keyCopy = keyCopy, keyGeneration = keyGeneration),
        )

    /**
     * Every candidate for a link of the chain back through the keys of the group [groupUuid], see
     * [GroupKeyChain], ascending by generation, from the second to the group's current one, and
     * oldest first within a generation: the committer's, stored with its Commit, see
     * [AddGroupMembersBody.previousKey], first, and those its members published since, see
     * [publishKeyLink].
     *
     * A `404` is a caller who is no current member of the group, a former one included, or the
     * proxy in front of the server answering as it is redeployed.
     */
    suspend fun keyLinks(groupUuid: String): Result<List<GroupKeyLinkDto>, NetworkError> =
        client.get<List<GroupKeyLinkDto>>(
            route = DuckCountRoutes.Group.KEY_LINKS,
            pathParams = mapOf("group_uuid" to groupUuid),
        )

    /**
     * Publishes [previousKey] as the caller's own link at [generation] of the chain back through
     * the keys of the group [groupUuid], see [GroupSync]. The empty `204` stores it, and a `409`
     * says the caller published one there already, which is as good: the server keeps one per
     * member and generation, and replaces none. A `404` is a caller who is no current member of the
     * group, or the proxy in front of the server answering as it is redeployed.
     */
    suspend fun publishKeyLink(
        groupUuid: String,
        generation: Long,
        previousKey: EncryptedFieldDto,
    ): EmptyResult<NetworkError> =
        when (
            val sent = client.put<Unit, PublishGroupKeyLinkBody>(
                route = DuckCountRoutes.Group.KEY_LINK,
                pathParams = mapOf("group_uuid" to groupUuid, "generation" to generation.toString()),
                body = PublishGroupKeyLinkBody(previousKey = previousKey),
            )
        ) {
            is Result.Success -> sent
            is Result.Error -> if (sent.error == NetworkError.CONFLICT) Result.Success(Unit) else sent
        }

    /**
     * Drops the caller's own copy of the key of the group [groupUuid], as they leave it, see
     * [GroupLeaver]. A copy the server does not hold, or a group it does not, is no error, so
     * dropping it twice does no harm.
     */
    suspend fun deleteKeyCopy(groupUuid: String): EmptyResult<NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Group.KEY_COPY,
            pathParams = mapOf("group_uuid" to groupUuid),
        )

    /**
     * Takes the caller out of the group [groupUuid], see [GroupLeaveAnswer] for what the server can
     * answer: it no longer lists the group to them, nor them to its members, and drops whatever
     * waits for them there, the Welcome among it, which they would otherwise never acknowledge,
     * and their copy of its key.
     * Any member may leave but its owner, whom the server refuses. Their leaf stays in the group's
     * tree until a member's device removes it, the creator's at once and any other's once
     * [DEPARTED_GRACE] has gone by, see [GroupSync], which the server wakes every current member
     * for as it takes a current member's leave, see [push.PushSignal.Kind.GROUP_MEMBER_LEFT].
     */
    suspend fun leave(groupUuid: String): GroupLeaveAnswer =
        leaveAnswerOf(
            sent = client.delete<Unit>(
                route = DuckCountRoutes.Group.MEMBERSHIP,
                pathParams = mapOf("group_uuid" to groupUuid),
            ),
        )

    /** What the server answered [request], one of the changes [GroupMembershipAnswer] is about. */
    private suspend fun changeMembers(request: suspend () -> HttpResponse): GroupMembershipAnswer {
        // Read off the response as it comes back, as for a creation.
        var refusal: GroupMembershipAnswer? = null

        val sent = client.execute<Unit> {
            request().also { response ->
                refusal = membershipRefusalOf(status = response.status, headers = response.headers) {
                    response.bodyAsText()
                }
            }
        }

        return when (sent) {
            is Result.Success -> GroupMembershipAnswer.Made
            is Result.Error -> refusal ?: membershipAnswerOf(error = sent.error)
        }
    }

    /**
     * Posts [message], an MLS application message in the group [groupUuid], to every other current
     * member, see [GroupPostAnswer] for what the server can answer. Any current member may post,
     * within an allowance of their own, all their groups together.
     *
     * The server mints a new message for every post, so one sent again whose answer was lost
     * reaches the members twice: only what reading it twice changes nothing of is sent here, see
     * [GroupProfileKeyDelivery].
     */
    suspend fun postMessage(groupUuid: String, message: ByteArray): GroupPostAnswer {
        // Read off the response as it comes back, as for a creation.
        var refusal: GroupPostAnswer? = null

        val sent = client.execute<PostGroupMessageResponse> {
            client.httpClient.post(urlString = DuckCountRoutes.Group.MESSAGES.replace(":group_uuid", groupUuid)) {
                setBody(PostGroupMessageBody(ciphertext = Base64.Default.encode(message)))
                contentType(ContentType.Application.Json)
            }.also { response ->
                refusal = postRefusalOf(status = response.status, headers = response.headers)
            }
        }

        return when (sent) {
            is Result.Success -> GroupPostAnswer.Posted
            is Result.Error -> refusal ?: postAnswerOf(error = sent.error)
        }
    }

    /**
     * Acknowledges messages the caller is done with in the group [groupUuid], in as many requests
     * of at most [MAX_GROUP_ACKNOWLEDGEMENT_BATCH] as it takes, stopping at the first that fails:
     * what was not acknowledged is handed back by the next listing.
     *
     * UUIDs that name nothing, or messages not waiting for the caller, are ignored, so
     * acknowledging a message twice does no harm.
     */
    suspend fun acknowledgeGroupMessages(groupUuid: String, messageUuids: List<String>): EmptyResult<NetworkError> {
        messageUuids.chunked(MAX_GROUP_ACKNOWLEDGEMENT_BATCH).forEach { batch ->
            val acknowledgement = client.post<Unit, AcknowledgeGroupMessagesBody>(
                route = DuckCountRoutes.Group.ACKNOWLEDGEMENTS,
                pathParams = mapOf("group_uuid" to groupUuid),
                body = AcknowledgeGroupMessagesBody(messageUuids = batch),
            )

            if (acknowledgement is Result.Error) return acknowledgement
        }

        return Result.Success(Unit)
    }
}

/**
 * What the server answered `POST /groups`, as far as what became of the group goes.
 *
 * The server writes nothing unless it takes the whole creation, so every refusal is final for
 * that request, and only [Failed] leaves open whether the group was stored.
 */
sealed interface GroupCreationAnswer {

    /**
     * The empty `201`: the server took the group, under the UUID the creator's device derived
     * already, so it has nothing to hand back.
     */
    data object Created : GroupCreationAnswer

    /**
     * The caller already created a group with this nonce: an earlier send of this very creation,
     * whose answer never came back, since every group gets a nonce of its own. As good as
     * [Created].
     *
     * Checked ahead of [DeletedInvitees] and [RateLimited], so that a resend of a creation the
     * server stored hears so whatever became of the invitees or the limit since.
     */
    data object AlreadyExists : GroupCreationAnswer

    /**
     * The `410` naming every invitee with no account, whether they deleted it or it never
     * existed: none of them could ever join, so nothing was written.
     */
    data class DeletedInvitees(val userUuids: List<String>) : GroupCreationAnswer

    /**
     * The `429` of the server's limit on the groups one user creates, which lets one through
     * again in [retryAfterSeconds], the `Retry-After` it carries. Nothing was written: the limit
     * is only counted once the creation would be stored.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupCreationAnswer

    /**
     * The server refused the request itself, whose body it could not take: nothing was written,
     * and the same body would be refused again.
     */
    data class Refused(val error: NetworkError) : GroupCreationAnswer

    /**
     * No answer that says what became of the group: the request may never have left, or the
     * group may have been stored and the answer lost on the way back, the proxy's `404` while the
     * API is being redeployed and a `401` that a refresh may still turn around included. Only
     * sending it again can tell.
     */
    data class Failed(val error: NetworkError) : GroupCreationAnswer
}

/**
 * What a `POST /groups` answered with [status] and [headers] comes to, for the answers read off
 * the response itself, or null for the others, see [creationAnswerOf]: the `410`, naming whom
 * its body, which [deleted] reads, says has no account, and the `429`, with its `Retry-After`.
 */
internal suspend fun creationRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    deleted: suspend () -> DeletedInviteesResponse,
): GroupCreationAnswer? =
    when (status) {
        HttpStatusCode.Gone -> GroupCreationAnswer.DeletedInvitees(userUuids = deleted().deletedUserUuids)

        HttpStatusCode.TooManyRequests -> GroupCreationAnswer.RateLimited(
            retryAfterSeconds = headers.retryAfterSeconds(),
        )

        else -> null
    }

/**
 * What a `POST /groups` that failed with [error] answered, for the answers whose response carries
 * nothing more: the `410` and `429` are read off the response itself, see [creationRefusalOf].
 *
 * A `400`, `413` or `422` is the server refusing the body, which it does before writing anything.
 */
internal fun creationAnswerOf(error: NetworkError): GroupCreationAnswer =
    when (error) {
        NetworkError.CONFLICT -> GroupCreationAnswer.AlreadyExists

        NetworkError.BAD_REQUEST,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        -> GroupCreationAnswer.Refused(error = error)

        else -> GroupCreationAnswer.Failed(error = error)
    }

/**
 * What the server answered `POST /groups/{group_uuid}/messages`, see [GroupApi.postMessage]. Every
 * refusal writes nothing.
 */
sealed interface GroupPostAnswer {

    /** The `200`: the server holds the message for every other current member. */
    data object Posted : GroupPostAnswer

    /**
     * The `409`: the caller is the group's one current member, so there is nobody to post to, and
     * nobody the message was for.
     */
    data object NoOtherMembers : GroupPostAnswer

    /**
     * The `404`: the caller is no current member of the group, as once its creator removed them,
     * which the Commit doing so tells their device, or the proxy in front of the server answering
     * while it is being redeployed. Either way, nothing to retry now.
     */
    data object NotMember : GroupPostAnswer

    /**
     * The `429` of the server's limit on the messages one user posts, all their groups together,
     * which lets one through again in [retryAfterSeconds], the `Retry-After` it carries.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupPostAnswer

    /**
     * Any other failure: the request may never have left, or the message may have been stored and
     * the answer lost on the way back.
     */
    data class Failed(val error: NetworkError) : GroupPostAnswer
}

/**
 * What a `POST /groups/{group_uuid}/messages` answered with [status] and [headers] comes to, for
 * the one answer read off the response itself, or null for the others, see [postAnswerOf]: the
 * `429`, with its `Retry-After`.
 */
internal fun postRefusalOf(status: HttpStatusCode, headers: Headers): GroupPostAnswer? =
    when (status) {
        HttpStatusCode.TooManyRequests -> GroupPostAnswer.RateLimited(retryAfterSeconds = headers.retryAfterSeconds())
        else -> null
    }

/**
 * What a `POST /groups/{group_uuid}/messages` that failed with [error] answered, the `429` aside,
 * which is read off the response itself, see [postRefusalOf].
 */
internal fun postAnswerOf(error: NetworkError): GroupPostAnswer =
    when (error) {
        NetworkError.CONFLICT -> GroupPostAnswer.NoOtherMembers
        NetworkError.NOT_FOUND -> GroupPostAnswer.NotMember
        else -> GroupPostAnswer.Failed(error = error)
    }

/**
 * What the server answered a change to the members of a group, `POST` or `DELETE
 * /groups/{group_uuid}/members`, the removal of its departed members, `DELETE
 * /groups/{group_uuid}/departed`, or the owner's replacement of a Commit, `POST
 * /groups/{group_uuid}/commits/replacement`: every route that stores a Commit.
 *
 * The server writes nothing unless it takes the whole change, so every refusal is final for that
 * request, and only [Failed] leaves open whether the change was stored.
 */
sealed interface GroupMembershipAnswer {

    /** The empty `200`: the server took the change, and delivers what it carries. */
    data object Made : GroupMembershipAnswer

    /**
     * The caller already made a change to this group with this nonce: an earlier send of this very
     * change, whose answer never came back, since every change gets a nonce of its own. As good as
     * [Made].
     *
     * Checked once the caller is known to be allowed the change, and ahead of
     * [KeyGenerationNotCurrent], [DeletedMembers], [RateLimited] and the refusals of what the change
     * names, so that a resend of a change the server stored hears so whatever became of the group,
     * its members or the limit since.
     */
    data object AlreadyMade : GroupMembershipAnswer

    /**
     * The JSON `409` of a Commit built in an epoch that is not the group's current one,
     * [keyGeneration]: another member's Commit for that epoch got there first, the server storing
     * one per epoch, see [GroupSync]. Nothing was written, and the Commit is of no use any more:
     * the members apply the other one.
     */
    data class KeyGenerationNotCurrent(val keyGeneration: Long) : GroupMembershipAnswer

    /**
     * The `410` of an addition naming every new member with no account, whether they deleted it
     * or it never existed: none of them could ever join, so nothing was written.
     */
    data class DeletedMembers(val userUuids: List<String>) : GroupMembershipAnswer

    /**
     * The `429` of the server's limit on the changes one user makes to groups, every kind
     * together, which lets one through again in [retryAfterSeconds], the `Retry-After` it carries.
     * Nothing was written.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupMembershipAnswer

    /**
     * The server refused the change itself: a body it could not take, a target it does not list as
     * departed, or a replacement with nothing to replace, its `409` read as a `CONFLICT`. Nothing was
     * written, and the same request would be refused again.
     */
    data class Refused(val error: NetworkError) : GroupMembershipAnswer

    /**
     * No answer that says what became of the change: the request may never have left, or the
     * change may have been stored and the answer lost on the way back, the proxy's `404` while the
     * API is being redeployed and a `401` that a refresh may still turn around included. Only
     * sending it again can tell.
     */
    data class Failed(val error: NetworkError) : GroupMembershipAnswer
}

/**
 * What a change to a group's members that answered with [status] and [headers] comes to, for the
 * answers read off the response itself, or null for the others, see [membershipAnswerOf]: the
 * `409`s, told apart by their body, which [body] reads, JSON for an epoch, the plain text of a
 * replacement with nothing to replace, and any other plain text for a nonce sent already; the
 * `410`, naming whom its body says has no account; and the `429`, with its `Retry-After`.
 */
internal suspend fun membershipRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    body: suspend () -> String,
): GroupMembershipAnswer? =
    when (status) {
        HttpStatusCode.Conflict -> {
            val text = body()

            when {
                text.trim() == NOTHING_TO_REPLACE -> GroupMembershipAnswer.Refused(error = NetworkError.CONFLICT)

                else -> keyGenerationConflictOf(body = text)
                    ?.let { GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = it) }
                    ?: GroupMembershipAnswer.AlreadyMade
            }
        }

        HttpStatusCode.Gone -> GroupMembershipAnswer.DeletedMembers(
            userUuids = conflictJson.decodeFromString(DeletedInviteesResponse.serializer(), body()).deletedUserUuids,
        )

        HttpStatusCode.TooManyRequests -> GroupMembershipAnswer.RateLimited(
            retryAfterSeconds = headers.retryAfterSeconds(),
        )

        else -> null
    }

/**
 * What a change to a group's members that failed with [error] answered, for the answers whose
 * response carries nothing more, see [membershipRefusalOf].
 *
 * A `400`, `413` or `422` is the server refusing the body, which it does before writing anything,
 * or a target the route does not take. A `404` is no refusal, as for a creation: the server
 * answers one for a group it no longer holds, or a caller who is no member of it, a member removed
 * as their change waited, whether or not it took the change, while the proxy in front of it
 * answers one to every request while it is being redeployed. Taken for a refusal, the Commit of a
 * change the server stored, its answer lost, would be discarded on this device alone, leaving it
 * in an epoch every member left. A change pending in a group the server no longer lists is settled
 * by the group sync instead, see [GroupMembershipSender.settleUnlisted].
 */
internal fun membershipAnswerOf(error: NetworkError): GroupMembershipAnswer =
    when (error) {
        NetworkError.CONFLICT -> GroupMembershipAnswer.AlreadyMade

        NetworkError.BAD_REQUEST,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        -> GroupMembershipAnswer.Refused(error = error)

        else -> GroupMembershipAnswer.Failed(error = error)
    }

/**
 * What the server answered `PUT /groups/{group_uuid}`, an edit of a group's information, see
 * [GroupApi.edit]. Nothing is written but on [Edited].
 */
sealed interface GroupEditAnswer {

    /** The empty `204`: the server holds the version. */
    data object Edited : GroupEditAnswer

    /**
     * The JSON `409` of a version sealed under a generation of the group's keys that is not its
     * current one, [keyGeneration]: Commits this device has yet to read, see [GroupSync]. Checked
     * ahead of the revision.
     */
    data class KeyGenerationNotCurrent(val keyGeneration: Long) : GroupEditAnswer

    /**
     * The plain `409` of a revision that does not come after the one the server holds: an earlier
     * edit whose answer was lost, see [GroupEditor].
     */
    data object RevisionNotNewer : GroupEditAnswer

    /**
     * Any other answer: the `403` of a member who is not its owner, the `404` of one who is no
     * member at all or of the proxy in front of the server as it is redeployed, a body it could not
     * take, or no answer at all, which leaves open whether the edit was stored.
     */
    data class Failed(val error: NetworkError) : GroupEditAnswer
}

/**
 * What an edit that answered with [status] comes to, for the answers read off the response
 * itself, or null for the others: the `409`s, told apart by their body, which [body] reads, JSON
 * for a generation and plain text for a revision.
 */
internal suspend fun editRefusalOf(status: HttpStatusCode, body: suspend () -> String): GroupEditAnswer? =
    when (status) {
        HttpStatusCode.Conflict -> keyGenerationConflictOf(body = body())
            ?.let { GroupEditAnswer.KeyGenerationNotCurrent(keyGeneration = it) }
            ?: GroupEditAnswer.RevisionNotNewer

        else -> null
    }

/**
 * The generation the JSON `409` [body] names as the group's current one, see
 * [KeyGenerationConflictResponse], or null when it is a plain text `409`. A JSON body that does not
 * read as the API writes it throws, which the client reads as one it could not read, see
 * [networking.Client.execute].
 */
private fun keyGenerationConflictOf(body: String): Long? {
    val fields = runCatching { conflictJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

    return conflictJson.decodeFromJsonElement(KeyGenerationConflictResponse.serializer(), fields).keyGeneration
}

/**
 * What the server answered `DELETE /groups/{group_uuid}/membership`, see [GroupApi.leave]. It
 * writes nothing unless it takes the leave, and leaving a group the caller already left changes
 * nothing, so the same request can be sent until it is heard.
 */
sealed interface GroupLeaveAnswer {

    /**
     * The empty `200`: the caller is no member of the group now, whether this request took them
     * out, or an earlier one whose answer was lost, or its creator removed them, the Commit doing
     * so dropped with the rest of what waited for them.
     */
    data object Left : GroupLeaveAnswer

    /**
     * The `404`: the server holds no such group, or the caller was never a member of it, so there
     * is nothing to leave. Or the proxy in front of the server answering while it is being
     * redeployed, which leaves the caller a member: nothing in the answer tells the two apart, as
     * for a change to a group's members, see [membershipAnswerOf]. The server never answers it for
     * a group it lists to the caller, a current member, whose leave it takes, or refuses with a
     * `403` if they are its owner. So the sync keeps the leave owed, and sends it again, while the
     * server lists the group, and forgets it once the server no longer does, see
     * [GroupSync.leaveDeclined]. [GroupLeaver], with no listing to go by, keeps it owed for the
     * sync.
     */
    data object NotMember : GroupLeaveAnswer

    /**
     * Any other answer: the request may never have left, or the leave may have been taken and the
     * answer lost on the way back, a `401` that a refresh may still turn around included. Only
     * sending it again can tell. The `403` refusing the group's owner is one, the client reading it
     * as no answer it knows: the sync never asks for that, see [GroupSync], and the user asks for it
     * only in a group handed to them as its creator deleted their account, see [GroupLeaver].
     */
    data class Failed(val error: NetworkError) : GroupLeaveAnswer
}

/** What a `DELETE /groups/{group_uuid}/membership` that came to [sent] answered, see [GroupLeaveAnswer]. */
internal fun leaveAnswerOf(sent: EmptyResult<NetworkError>): GroupLeaveAnswer =
    when (sent) {
        is Result.Success -> GroupLeaveAnswer.Left

        is Result.Error -> when (sent.error) {
            NetworkError.NOT_FOUND -> GroupLeaveAnswer.NotMember
            else -> GroupLeaveAnswer.Failed(error = sent.error)
        }
    }
