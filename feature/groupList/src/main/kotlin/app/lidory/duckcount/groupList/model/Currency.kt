package app.lidory.duckcount.groupList.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Currency(
    val uuid: String = Uuid.random().toString(),
    val name: String,
    val symbol: String,
    val hasSubunits: Boolean,
)
