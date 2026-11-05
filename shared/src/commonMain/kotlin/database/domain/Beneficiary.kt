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

    /**
     * How many parts of what the fixed shares and percentages leave the share was set as, next to
     * the parts of the others, null for one set otherwise. Like [sharePercentage], it is what [share]
     * is worked out again from. An expense using any weight states one for everybody sharing what is
     * left, `1` for those who were given none, for whom [isShareFixed] does not hold, unlike who it
     * was set by hand for.
     */
    val shareWeight: String? = null,
)