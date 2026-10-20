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
 * longer holds them either, and only then goes. One the user removed waits for their
 * profile key to rotate first, see [rotatesProfileKey].
 *
 * Their name is not here but in [UserEntity], which is what the contact list shows, and
 * the group the two of them talk through is not here either but in [DirectChannelEntity]:
 * a channel outlives the contact, since somebody the user removed can still be the only
 * person holding the key to an expense they share.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class ContactEntity(
    @PrimaryKey(autoGenerate = false) val userUuid: String,

    /**
     * The key to the contact's profile, which they handed over: in the card that made them
     * a contact, and since in every [ProfileKeyUpdate][contact.ProfileKeyUpdate] they sent.
     */
    val profileEncryptionKey: ByteArray,

    /**
     * The partial hash of the key to the user's own profile this contact was last handed,
     * or null when which one is not known, see `profileEncryptionKeyPartialHash`.
     *
     * The key changes when the user removes a contact, see
     * [ProfileRepository.rotateProfileKey][profile.ProfileRepository.rotateProfileKey], and
     * every contact left goes on reading the profile as it was until handed the new one. So
     * one whose hash is not the current key's is owed it, see
     * [ProfileKeyDelivery][contact.ProfileKeyDelivery]. It starts as the key the card that
     * made them a contact carried, the user's request to them or the user's acceptance of
     * theirs, which a removal since may have replaced, and follows every key handed them after,
     * pushed or answering their request for it, see [ProfileKeyPull][contact.ProfileKeyPull].
     *
     * Kept in the vault with the contact, so a log-out or a reinstall does not hand every
     * contact the key again, nor forget the ones still owed it.
     */
    val handedProfileKeyHash: String?,

    /**
     * The safety number the user compared with this contact and marked verified, or null when
     * they have not, or cleared the mark, see [SafetyNumbers][contact.SafetyNumbers].
     *
     * The number itself rather than a flag: the mark counts only while it is the number computed
     * now, from the user's identity and the one pinned for the contact, so it stops counting on
     * its own should either ever differ from the ones it was computed from, rather than vouching
     * for keys nobody compared.
     *
     * Kept in the vault with the contact, so a log-out or a reinstall does not forget it. A
     * removal does, see [ContactDao.add][database.data.local.dao.ContactDao.add].
     */
    val verifiedSafetyNumber: String?,

    /**
     * Whether the vault agrees with this row: it holds the contact, or for a removed one,
     * it no longer does. A removed contact never gets there, it is deleted instead.
     */
    val isBackedUp: Boolean,

    /** Whether the user no longer has this contact, see the class documentation. */
    val isRemoved: Boolean = false,

    /**
     * Whether this removal waits for the key to the user's profile to rotate before it
     * reaches the vault, see
     * [ContactRepository.backUp][database.data.repository.ContactRepository.backUp]. Only
     * one the user made does, never a contact's notice that they removed the user: the key
     * only ever rotates on the user's own action.
     *
     * Kept on the device until the key has rotated: a removal that reached the vault first
     * would, after a log-out, leave the removed contact holding the current key for good, with
     * nothing left saying it has to change. One that did not comes back from the vault as a
     * contact, which is consistent with the key they hold.
     */
    val rotatesProfileKey: Boolean = false,
)
