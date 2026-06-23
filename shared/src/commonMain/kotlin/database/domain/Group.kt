package database.domain

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Group(
    val uuid: String = Uuid.random().toString(),
    val description: String,
    val title: String,
    val currency: Currency,
)
