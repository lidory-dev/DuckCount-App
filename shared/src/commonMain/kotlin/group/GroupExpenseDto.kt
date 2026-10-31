package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Body of `POST /groups/{group_uuid}/expenses`, which any current member may send.
 *
 * The expense's UUID is not sent: the server derives it from [nonce] and the caller's UUID, as the
 * caller's device did before sealing the expense bound to it, see [GroupExpenseWriter]. The same
 * nonce sent again answers `409`, so a create whose answer was lost, sent again once with it, is
 * not stored twice. One whose second answer is lost too is the failure it is, and saving it again
 * draws a new nonce, which makes another expense.
 */
@Serializable
data class CreateGroupExpenseBody(
    /** 16 random bytes in standard Base64, as `newGroupExpenseId` in lib-duckcount draws them. */
    val nonce: String,

    /**
     * The generation of the group's key [content] is sealed under, which the server takes only when
     * it is the group's current one, see [GroupExpenseWriteAnswer.KeyGenerationNotCurrent].
     */
    @SerialName("key_generation")
    val keyGeneration: Long,

    /** The expense's first version, sealed and signed, see [sealGroupExpense]. */
    val content: EncryptedFieldDto,
)

/**
 * Body of `PUT /groups/{group_uuid}/expenses/{expense_uuid}`, which any current member may send: the
 * expense's next version, a deletion and a restore included.
 */
@Serializable
data class UpdateGroupExpenseBody(
    /**
     * The revision [content] is signed at, which the server takes only when it is one past the one
     * it holds, see [GroupExpenseWriteAnswer.RevisionNotNext].
     */
    val revision: Long,

    /** The generation of the group's key [content] is sealed under, as for a create, see [CreateGroupExpenseBody]. */
    @SerialName("key_generation")
    val keyGeneration: Long,

    /** The version, sealed and signed, see [sealGroupExpense]. */
    val content: EncryptedFieldDto,
)

/**
 * Mirrors the server's `ListedGroupExpense`: one expense of a group as `GET
 * /groups/{group_uuid}/expenses` and its changes list it, at the version it now stands at.
 *
 * Everything the expense says is in [content], sealed under the group's key of [keyGeneration] by
 * whoever wrote this version, and signed by them, see [GroupExpenseOpener]: the server can tell
 * neither who paid nor who benefits. [revision] is the server's word, which the signature holds it
 * to: a version listed at another revision than its own does not verify.
 */
@Serializable
data class ListedGroupExpenseDto(
    val uuid: String,

    val revision: Long,

    @SerialName("key_generation")
    val keyGeneration: Long,

    val content: EncryptedFieldDto,
)

/**
 * Body of `GET /groups/{group_uuid}/expenses` asked for its first page: the newest of the group's
 * expenses, by when the server took their creation, [nextCursor] to ask for the next page with,
 * null when this is the last, and where the caller's feed of the group's expenses stood when it
 * was read, [feedToken], see [ListGroupExpenseChangesResponse].
 *
 * Each expense comes as the JSON the server wrote, a [ListedGroupExpenseDto] to this build: it is
 * kept that way, so that what a newer build reads of it is still there, see [GroupExpenseSync].
 */
@Serializable
data class ListGroupExpensesResponse(
    val expenses: List<JsonObject>,

    @SerialName("next_cursor")
    val nextCursor: String?,

    @SerialName("feed_token")
    val feedToken: String,
)

/** Body of `GET /groups/{group_uuid}/expenses` asked for a later page, see [ListGroupExpensesResponse]. */
@Serializable
data class ListGroupExpensesPage(
    val expenses: List<JsonObject>,

    @SerialName("next_cursor")
    val nextCursor: String?,
)

/**
 * Body of `GET /groups/{group_uuid}/expenses/changes`: what changed in the group's expenses since a
 * place in the caller's feed of them, each expense once, as it now stands, as the JSON the server
 * wrote, see [ListGroupExpensesResponse]; where the page leaves the feed, [feedToken]; and whether
 * more follow already, [hasMore]. Nothing is ever listed as removed: a deletion is a version like
 * any other, and a member losing the group loses its feed with it.
 */
@Serializable
data class ListGroupExpenseChangesResponse(
    val expenses: List<JsonObject>,

    @SerialName("feed_token")
    val feedToken: String,

    @SerialName("has_more")
    val hasMore: Boolean,
)

/**
 * Body of `GET /groups/{group_uuid}/expenses/{expense_uuid}/history`: the versions of a group
 * expense the server keeps, [current], the one its listing and changes list, and [previous], those
 * its updates replaced, newest first, the last nine of them, see [GroupExpenseHistory].
 */
@Serializable
data class GroupExpenseHistoryResponse(
    val current: GroupExpenseHistoryVersionDto,

    val previous: List<GroupExpenseHistoryVersionDto>,
)

/**
 * Mirrors the server's `GroupExpenseVersion`: one version of a group expense as its history holds
 * it, see [GroupExpenseHistoryResponse], sealed as it was stored, under its own generation of the
 * group's key, see [ListedGroupExpenseDto], and when the server took it.
 */
@Serializable
data class GroupExpenseHistoryVersionDto(
    val revision: Long,

    @SerialName("key_generation")
    val keyGeneration: Long,

    val content: EncryptedFieldDto,

    /**
     * When the server took this version, as the expense was created or the update that made it
     * was, RFC 3339 in UTC to the millisecond, as the API writes it: the server's word, which
     * nothing signs.
     */
    @SerialName("revised_at")
    val revisedAt: String,
)

/**
 * Body of the `409` refusing a version of a group expense, or of the group's information, sealed
 * under a key that is not the group's current one, see [GroupExpenseWriteAnswer.KeyGenerationNotCurrent]
 * and [GroupEditAnswer.KeyGenerationNotCurrent], or a Commit built in an epoch the group left, see
 * [GroupMembershipAnswer.KeyGenerationNotCurrent]: JSON, as no other `409` of those routes is but
 * the revision's.
 */
@Serializable
internal data class KeyGenerationConflictResponse(
    @SerialName("key_generation")
    val keyGeneration: Long,
)

/**
 * Body of the `409` refusing an update whose revision is not one past the stored one, see
 * [GroupExpenseWriteAnswer.RevisionNotNext].
 */
@Serializable
internal data class RevisionConflictResponse(
    val revision: Long,
)
