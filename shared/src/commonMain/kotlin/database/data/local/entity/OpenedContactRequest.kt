package database.data.local.entity

/**
 * A [ContactRequestEntity] that has been opened, read with the columns opening it filled
 * in, which its queries guarantee are there.
 */
class OpenedContactRequest(
    val uuid: String,
    val senderUserUuid: String,
    val mlsGroupId: ByteArray,
    val profileEncryptionKey: ByteArray,
    val senderFullName: String?,
)
