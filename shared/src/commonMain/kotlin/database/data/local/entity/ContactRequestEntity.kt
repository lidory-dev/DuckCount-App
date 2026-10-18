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
     * The server consumes a request as it takes the acceptance, and the request leaves its
     * listing however it goes: refused, cleared by its sender accepting the user's own
     * request, or with their account. So an acceptance whose answer never came back, timed
     * out or abandoned by a screen closing, is settled by asking the server what became of
     * the request, which it keeps a record of acceptances for: completed when the acceptance
     * took it, its sender having the user as a contact by then, and this row holding the
     * only copy of the group and the profile key that lead back to them; dropped otherwise.
     * Completing it pins them, so the row stays until the server can say, and that can be
     * done; only a sender who can never be pinned, a different identity having been pinned
     * for them since, has it dropped. The server keeps the record until this device
     * acknowledges it, or for 90 days, the session lifetime: an answer this device could not
     * get once in that time reads as gone, and the request is dropped then.
     */
    val isAcceptanceSent: Boolean = false,

    /**
     * The partial hash of the key to the user's own profile the acceptance carried, set with
     * [isAcceptanceSent], which becomes what the sender was handed once they are a contact,
     * see [ContactEntity.handedProfileKeyHash].
     *
     * Null while no acceptance has gone out, and when attempts whose answers never came back
     * carried different keys, a removal having rotated it in between: the server takes one
     * of them at most, and nothing here says which. The sender is then handed the current key
     * once a contact, whichever they hold.
     */
    val handedProfileKeyHash: String? = null,
)
