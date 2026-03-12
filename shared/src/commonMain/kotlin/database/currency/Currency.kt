package database.currency

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Currency(
    val uuid: String = Uuid.random().toString(),
    val name: String,
    val symbol: String,
    val hasSubunits: Boolean,
)
