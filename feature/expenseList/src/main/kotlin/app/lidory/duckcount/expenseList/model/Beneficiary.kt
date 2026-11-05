package app.lidory.duckcount.expenseList.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Beneficiary(
    val uuid: String = Uuid.random().toString(),
    val expenseUuid: String,
    val userUuid: String,
    val share: String,
    val isShareFixed: Boolean = false,

    /**
     * The percentage of the expense's amount the share was set as, null for one set as an amount or
     * split. [isShareFixed] holds for it too, a percentage being set by hand.
     */
    val sharePercentage: String? = null,

    /**
     * How many parts of what the fixed shares and percentages leave the share was set as, null for
     * one set otherwise. [isShareFixed] tells who it was set by hand for from who counts for `1`
     * because somebody else's was, whom the library states one for too.
     */
    val shareWeight: String? = null,
)
