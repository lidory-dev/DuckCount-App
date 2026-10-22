@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Server representation of a one-time expense as returned by `GET /expenses`.
 *
 * Matches the DuckCount-API contract exactly. Every content field is an
 * `EncryptedField` the server cannot read, sealed under the expense's own key,
 * which reaches its participants over their direct-message channels; see
 * `expense.OneTimeExpenseSealing`.
 *
 * `signature` holds the creditor's signature over all the rest but the `uuid`,
 * sealed like the other fields. It is what everything here is trusted on,
 * `creditor_uuid` and the beneficiary list included.
 *
 * Whether the expense was paid back is not part of that: `is_settled` is the creditor's
 * mark saying so, signed and sealed apart, null when they never made one, see
 * `expense.OneTimeExpenseSettledMark`.
 *
 * `key_copy` is the caller's own sealed copy of that key, null when they have not
 * stored one; see `expense.OneTimeExpenseKeyRepository`.
 *
 * There is no `group_uuid`: a one-time expense is by definition not in a group.
 * There is no deletion flag either, the server hard-deletes.
 */
@Serializable
data class ExpenseDto(
    val uuid: String,
    val creditor_uuid: String,
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val beneficiaries: List<BeneficiaryDto>,
    val signature: EncryptedFieldDto,
    val key_copy: EncryptedFieldDto?,
    val is_settled: EncryptedFieldDto?,
)

/**
 * Body of `GET /expenses` asked for its first page: the newest of the one-time expenses
 * the caller is on, by when the server took them, [next_cursor] to ask for the next page
 * with, null when this is the last, and where the caller's feed of changes stood when it
 * was read, [feed_token], see [ListOneTimeExpenseChangesResponse].
 *
 * Each expense comes as the JSON the server wrote, an [ExpenseDto] to this build: it is
 * kept that way, so that what a newer build reads of it is still there, see
 * `expense.OneTimeExpenseSync`.
 */
@Serializable
data class ListOneTimeExpensesResponse(
    val expenses: List<JsonObject>,
    val next_cursor: String?,
    val feed_token: String,
)

/** Body of `GET /expenses` asked for a later page, see [ListOneTimeExpensesResponse]. */
@Serializable
data class ListOneTimeExpensesPage(
    val expenses: List<JsonObject>,
    val next_cursor: String?,
)

/**
 * Body of `GET /expenses/changes`: what changed in the one-time expenses the caller is on
 * since a place in their feed. The ones they see that changed, as they now stand, each
 * once, as the JSON the server wrote, see [ListOneTimeExpensesResponse]; those they no
 * longer see, [removed]; where the page leaves the feed, [feed_token]; and whether more
 * follow already, [has_more].
 */
@Serializable
data class ListOneTimeExpenseChangesResponse(
    val expenses: List<JsonObject>,
    val removed: List<String>,
    val feed_token: String,
    val has_more: Boolean,
)

/**
 * Body of the `410` that `POST /expenses` and `PUT /expenses/{uuid}` refuse an expense naming
 * people who deleted their account with: who they are, sorted by UUID, never none. Every one an
 * expense being created names, and only the ones an edit adds: one the expense names already is
 * kept, whether or not they deleted their account since.
 */
@Serializable
data class DeletedBeneficiariesResponse(
    val deleted_user_uuids: List<String>,
)
