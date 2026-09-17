package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One of the user's contacts: somebody whose request they accepted, or who accepted
 * theirs.
 *
 * The vault is where contacts are kept, this is the device's copy. It exists because
 * the vault writes it needs can fail, and both kinds of change have to survive that:
 * the key a contact hands over can only be read once, out of an MLS message, and so can
 * a contact's notice that they removed the user. Each change lands here first, and is
 * retried against the vault until [isBackedUp].
 *
 * A removed contact therefore stays as a tombstone, [isRemoved], until the vault no
 * longer holds them either, and only then goes.
 *
 * Their name is not here but in [UserEntity], which is what the contact list shows.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class ContactEntity(
    @PrimaryKey(autoGenerate = false) val userUuid: String,

    /** The key to the contact's profile, which they handed over. */
    val profileEncryptionKey: ByteArray,

    /**
     * The MLS group the contact request created, the channel the two of them share.
     *
     * Null for a contact restored from the vault: the group's state lived in this
     * device's database, which a log-out or a reinstall does not keep. Null for a removed
     * contact too, whose group is deleted along with them.
     */
    val mlsGroupId: ByteArray?,

    /**
     * Whether the vault agrees with this row: it holds the contact, or for a removed one,
     * it no longer does. A removed contact never gets there, it is deleted instead.
     */
    val isBackedUp: Boolean,

    /** Whether the user no longer has this contact, see the class documentation. */
    val isRemoved: Boolean = false,
)
