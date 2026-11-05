package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Somebody the user blocked, from a contact request of theirs or as a contact, nothing coming from
 * them being taken in from then on.
 *
 * A contact is removed first, as any removal the user makes, see
 * [ContactRepository.block][database.data.repository.ContactRepository.block]: told, so that
 * their app drops the user and stops sending, which reads to them as nothing but a removal, and
 * owed a rotation of the key to the user's profile. A request is refused first, which its sender
 * is never told, see
 * [ContactRequestRepository.blockSender][database.data.repository.ContactRequestRepository.blockSender].
 * From then on:
 * - every request of theirs is refused unread, see
 *   [ContactRequestRepository.refresh][database.data.repository.ContactRequestRepository.refresh],
 *   and the signal it sends wakes nobody, see [PushSignal.mayWake][push.PushSignal.mayWake];
 * - every direct message from them is acknowledged unread, see
 *   [DirectMessageSync][message.DirectMessageSync]: their answer to a request of the user's, which
 *   would make them a contact again, and the keys to their one-time expenses alike, none of which
 *   is asked for either, see
 *   [OneTimeExpenseKeyRepository.expectedFrom][expense.OneTimeExpenseKeyRepository.expectedFrom];
 * - the expenses they write in a group the two of them share are not announced, see
 *   [GroupExpenseUpdates][expense.GroupExpenseUpdates];
 * - and the user sends them no request, see
 *   [SendContactRequestError.Blocked][contact.SendContactRequestError.Blocked].
 *
 * What already is stays as it was: an expense of theirs this device opened before, and the groups
 * the two of them share, whose Commits and expenses are taken in as any member's, every member's
 * balances hanging on them. Nor are they told any of it, the server keeping no record of blocks:
 * only this device and the vault know.
 *
 * Unlike an [IdentityConflictEntity], which this device records for an identity in doubt and only
 * a log-out lifts, a block is the user's, and kept in the vault, see
 * [BlockList][vault.BlockList], so that it outlives a log-out, a reinstall or a new phone, and
 * only the user lifts it, see [isUnblocked]. Each change lands here first, and is carried to the
 * vault until [isBackedUp], as a contact's is, see [ContactEntity].
 */
@Entity
data class BlockedUserEntity(
    @PrimaryKey(autoGenerate = false) val userUuid: String,

    /**
     * The name the user saw them by as they blocked them, which the list of blocked users shows,
     * or null when there was none, a request whose sender's profile could not be read for one.
     */
    val fullName: String?,

    /** Whether the vault holds this row as it is. */
    val isBackedUp: Boolean = false,

    /**
     * Whether the user unblocked them, the row staying as a tombstone until the vault no longer
     * holds them either, and only then going: gone at once, the block would come back with the
     * vault after a log-out. Nothing a blocked user sent meanwhile is read again: it was
     * acknowledged unread, and they are nobody's contact once unblocked, until a request of either
     * of the two is accepted.
     */
    val isUnblocked: Boolean = false,
)
