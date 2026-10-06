package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A peer this device and the vault pin different MLS identities for.
 *
 * The vault keeps the identity first pinned for each contact, and this device pins them too,
 * neither ever replacing its pin with another. No flow in the app changes an identity, so
 * when the two disagree, one of them was most likely served by somebody other than the peer,
 * and nothing here can tell which. So the peer is blocked: no channel is built to them, no
 * key or request goes to them, none of their requests is accepted, every direct message
 * from them is acknowledged unread, and none of their one-time expenses is opened, see
 * [OneTimeExpenseKeyRepository][expense.OneTimeExpenseKeyRepository].
 *
 * What does not depend on who they are still goes through: removing them sends the notice
 * in the channel the two of them share, and a request from them is opened and listed, since
 * only accepting it answers them, and pins them once the server takes it.
 *
 * Recorded wherever the two pins meet: when the MLS client opens and puts the vault's pins
 * back, when [ContactRepository.restore][database.data.repository.ContactRepository.restore]
 * does the same after a login, and when
 * [ContactRepository.backUp][database.data.repository.ContactRepository.backUp] finds the
 * vault pinning someone else for a contact it carries there.
 *
 * Only a log-out lifts it, along with the device's pins.
 */
@Entity
class IdentityConflictEntity(
    @PrimaryKey(autoGenerate = false) val peerUuid: String,
)
