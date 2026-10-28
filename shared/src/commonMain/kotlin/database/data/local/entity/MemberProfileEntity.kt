package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The key to the profile of somebody other than the user that this device learned through the
 * groups the two of them are in, the best it learned, and the name read with it: what names a
 * member of a group who is not the user's contact, see `group.MemberProfileRepository`.
 *
 * A contact hands their key over in person, over the channel the two of them talk through, see
 * [ContactEntity.profileEncryptionKey], and is named by the contact list. A member who is not one
 * hands it to nobody that way: the creator of a group, who seated every member as a contact of
 * theirs, hands each member's key to the others as he seats somebody, and each member posts their
 * own into every group they join, again each time its creator seats somebody, and into each once
 * their key rotates. A key he relays only fills in for a member nothing is held for, see
 * [isFromOwner], so one held already, learned in another group and older, stays until its owner's
 * post replaces it. Of two from the owner, the one that came in the later message stays, see
 * [ownerKeySentAt].
 *
 * Kept whatever comes of the groups: a member who left, or was removed, still shows in the
 * balances and expenses of the groups the two of them shared. And kept for somebody who becomes a
 * contact too, who is named as one, and by this again should the two of them part.
 *
 * Backed up in the vault, see [isBackedUp], so that a log-out does not take the names of the
 * members with it: the groups brought back after one read nothing until their creator adds the
 * user back, and the names would be gone until then.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class MemberProfileEntity(
    @PrimaryKey(autoGenerate = false) val userUuid: String,

    /** The key to their profile, 32 bytes. */
    val profileEncryptionKey: ByteArray,

    /**
     * Whether [profileEncryptionKey] came from its owner, posted by them into a group the two of
     * them are in, or written by them as the creator of one, rather than relayed by the creator of
     * a group. One from its owner is theirs as it was when they sent it, and replaces whatever was
     * held but one of theirs that came in a message stored no earlier, see [ownerKeySentAt]; a
     * relayed one only fills in for a member nothing was held for, the creator possibly holding a
     * key of theirs that their owner replaced since.
     */
    val isFromOwner: Boolean,

    /**
     * When the server stored the latest message read that [profileEncryptionKey] came in from its
     * owner, their post or the Welcome, or the map, they wrote as the creator of a group, in
     * milliseconds since the epoch, see `group.GroupSync.takeProfileKeys`: what tells which of two
     * keys of theirs is the newer. A member only ever sends the key they hold then, so the later
     * message carries the newer key, and the server stamps every message of every group by the one
     * clock. Without it, an older post read after a newer one would win: the groups are read one
     * after the other, in no particular order, and one this device could not read in a pass is
     * read in a later one.
     *
     * The latest rather than the first, each message carrying the key held moving it up, see
     * `database.data.local.dao.MemberProfileDao.takeOwnerKey`: an older key a creator's Welcome or
     * map still carries, built before his rotation and sent after it, can come in a message stored
     * between two of his posts of the newer one, and only the time of the second keeps it out. Two
     * of their messages stored at the same millisecond tell nothing, and the key held stays.
     *
     * Null for a relayed key, which any key from its owner replaces, and for a row the vault
     * brought back until a message of its owner's carries its key again, see
     * `vault.MemberProfileBook`: any key from its owner replaces that one too. The
     * vault brings rows back once after a log-in, onto a device holding no MLS state, the log-out
     * or the reinstall before having taken it: every message the device reads from then on was
     * sent after that log-in, later than any the vault's keys came in, which a device holding the
     * session before it read.
     */
    val ownerKeySentAt: Long?,

    /** The name on their profile, or null when none was read yet. */
    val fullName: String?,

    /**
     * The partial hash of the key [fullName] was last read with, or a read under it settled
     * without one, the profile it opens being gone: one that differs from that of
     * [profileEncryptionKey] is a profile still to read. Null when none was read.
     */
    val nameKeyHash: String?,

    /** Whether the vault holds this key and name, see `vault.MemberProfileBook`. */
    val isBackedUp: Boolean,
)
