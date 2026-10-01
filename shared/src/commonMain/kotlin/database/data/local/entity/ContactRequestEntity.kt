package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact request waiting for the user.
 *
 * It starts as a copy of the server's row, and is **opened** once the user's client has
 * joined the group it created and read the sender's card: [mlsGroupId] and
 * [profileEncryptionKey] are then set together. Opening one is a one-time operation, a
 * group is only ever joined once, so what it yields is kept here rather than read again
 * from [ciphertext].
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class ContactRequestEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,

    /**
     * Who sent the request, as the server authenticated them. Opening the request confirms
     * it: the group the request created is only joined when they are its one other member.
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

    /**
     * Whether the user's acceptance has gone out, set just before it is sent, and cleared
     * again only when the server provably did not take it.
     *
     * The server consumes a request as it takes the acceptance, and holds no trace of it
     * afterwards, so an acceptance whose answer never came back, timed out or abandoned by
     * a screen closing, leaves this device no way to ask whether it landed. A request that
     * vanishes once this is set most likely did, and is completed rather than dropped: its
     * sender has the user as a contact by then, and this row holds the only copy of the
     * group and the profile key that lead back to them.
     */
    val isAcceptanceSent: Boolean = false,
)
