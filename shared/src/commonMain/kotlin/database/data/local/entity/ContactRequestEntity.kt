package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact request waiting for the user.
 *
 * It starts as a copy of the server's row, and is **opened** once the user's client has
 * joined the group it created and read the sender's card: [mlsGroupId] and
 * [profileEncryptionKey] are then set together. Opening one is a one-time operation,
 * joining spends the key package the request was sent to, so what it yields is kept here
 * rather than read again from [ciphertext].
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class ContactRequestEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,

    /**
     * Who sent the request. The server's word for it until the request is opened, then
     * the member MLS authenticated the sender's card as coming from.
     */
    val senderUserUuid: String,

    val ciphertext: String,
    val createdAt: String,

    /** The group the request created, which the user's answer is encrypted to. */
    val mlsGroupId: ByteArray? = null,

    /** The key to the sender's profile, which their card carried. */
    val profileEncryptionKey: ByteArray? = null,

    /** The name on the sender's profile, null until it has been read. */
    val senderFullName: String? = null,
)
