package database.beneficiary

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Beneficiary(
    val uuid: String = Uuid.random().toString(),
    val expenseUuid: String,
    val userUuid: String,
    val share: String,
)
