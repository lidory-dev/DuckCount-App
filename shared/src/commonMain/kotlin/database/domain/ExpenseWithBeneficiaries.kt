package database.domain

import app.lidory.duckcount.canonicalAmount
import kotlinx.datetime.LocalDateTime

data class ExpenseWithBeneficiaries(
    val uuid: String,
    val title: String,
    val timestamp: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,

    /**
     * Whether the expense is the payment settling up a group's debts. A one-time expense never
     * is one: paying it back is a mark on it, see [isSettled].
     */
    val isSettlement: Boolean = false,

    val currency: Currency,
    val groupUuid: String? = null,
    val isDeleted: Boolean = false,
    val beneficiaries: List<Beneficiary>,

    /**
     * Whether the creditor of a one-time expense marked it as paid back, see
     * `OneTimeExpenseSettledMarkEntity`. Read with the expense, never written with it: the mark
     * is signed and stored apart. Not [isSettlement], which makes the expense itself the
     * payment that settles a group's debts.
     */
    val isSettled: Boolean = false,

    /**
     * When it was marked as paid back, as its creditor's signed mark says, in the device's time
     * zone; null when it is not.
     */
    val settledAt: LocalDateTime? = null,

    /**
     * The revision of the version this device shows, see `ExpenseEntity.revision`: what an edit of
     * a group expense is written after, see `group.GroupExpenseWriter.edit`, so that one another
     * member made as the form was open is not written over unseen. Read with the expense, never
     * written with it; null for an expense read from nowhere.
     */
    val revision: Long? = null,

    /**
     * Who deleted the expense of a group, as the signed deletion flag this device shows says, see
     * `GroupExpenseDeletionEntity`; null when it is not deleted, and for a one-time expense. Read
     * with the expense, never written with it: the flag is signed and stored apart.
     */
    val deletedByUuid: String? = null,
)

/**
 * [this] with its title and description without the whitespace around them, as the user's own
 * writes take them, see `ExpenseRepository`, and as a group's are taken, see `GroupCreator`: the
 * form holds them as typed. Nothing inside them is collapsed.
 */
internal fun ExpenseWithBeneficiaries.trimmed(): ExpenseWithBeneficiaries =
    copy(title = title.trim(), description = description.trim())

/**
 * [this] with its amount and shares written the one way the library writes an amount, see
 * [canonicalAmount]: every write goes through here before it is sealed, so that nothing else ever
 * reaches the server, and the row the user's own list shows says what was sealed. Receivers refuse
 * anything else. Null when the library cannot read one of them as an amount in its currency, which
 * the form never lets through.
 */
internal fun ExpenseWithBeneficiaries.withCanonicalAmounts(): ExpenseWithBeneficiaries? =
    copy(
        amount = canonicalAmount(amount = amount, currency = currency) ?: return null,
        beneficiaries = beneficiaries.map { beneficiary ->
            beneficiary.copy(share = canonicalAmount(amount = beneficiary.share, currency = currency) ?: return null)
        },
    )
