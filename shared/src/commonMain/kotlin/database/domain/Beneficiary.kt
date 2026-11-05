package database.domain

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Beneficiary(
    val uuid: String = Uuid.random().toString(),
    val expenseUuid: String,
    val userUuid: String,
    val share: String,
    val isShareFixed: Boolean,

    /**
     * The percentage of the expense's amount the share was set as, null for one set as an amount or
     * split. [share] is what it comes to, and all that any sum reads: this is what it is worked out
     * again from when the amount is edited. A share set as a percentage is set by hand, so
     * [isShareFixed] holds for it too.
     */
    val sharePercentage: String? = null,
)