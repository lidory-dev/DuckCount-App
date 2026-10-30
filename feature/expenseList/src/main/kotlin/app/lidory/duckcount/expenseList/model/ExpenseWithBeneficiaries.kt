package app.lidory.duckcount.expenseList.model

import kotlinx.datetime.LocalDateTime

data class ExpenseWithBeneficiaries(
    val uuid: String,
    val timestamp: String,
    val title: String,
    val description: String,
    val amount: String,
    val creditorUuid: String,
    val isSettlement: Boolean,
    val currency: Currency,
    val beneficiaries: List<Beneficiary>,
    val groupUuid: String?,
    val isDeleted: Boolean,

    /** Whether the creditor of a one-time expense marked it as paid back. */
    val isSettled: Boolean = false,

    /** When they did, in the device's time zone; null when it is not settled. */
    val settledAt: LocalDateTime? = null,

    /**
     * The revision of the version shown, which an edit of a group expense is written after: one
     * another member made as the form was open is then not written over unseen.
     */
    val revision: Long? = null,
)
