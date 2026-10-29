package database.domain

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
)

/**
 * [this] with its title and description without the whitespace around them, as the user's own
 * writes take them, see `ExpenseRepository`, and as a group's are taken, see `GroupCreator`: the
 * form holds them as typed. Nothing inside them is collapsed.
 */
internal fun ExpenseWithBeneficiaries.trimmed(): ExpenseWithBeneficiaries =
    copy(title = title.trim(), description = description.trim())
