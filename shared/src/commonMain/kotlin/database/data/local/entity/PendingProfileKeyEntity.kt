package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact whose current profile key this device is missing, and what it has done about it,
 * see [ProfileKeyPull][contact.ProfileKeyPull].
 *
 * A contact hands the user a new key whenever they remove somebody, see
 * [ProfileKeyUpdate][contact.ProfileKeyUpdate], and the one message that carries it can go
 * missing with nobody noticing: sent into a group this device lost to a log-out or a reinstall,
 * it is unreadable here and acknowledged as such, while their device takes it as handed. All
 * that is left then is the key this device holds, which still opens a version of their profile,
 * the one sealed under it, and no newer. So a read of their profile that shows it no longer
 * opens the latest one, or none at all, writes this row, and the contact is asked for their key.
 *
 * It goes once a read opens their latest version, once they hand over the very key this device
 * holds, which is them saying it is their current one, see
 * [ContactRepository.receiveProfileKey][database.data.repository.ContactRepository.receiveProfileKey],
 * and with them when they stop being a contact, which is also what a request to an account its
 * owner deleted comes to, see
 * [ContactRepository.receiveAccountDeletion][database.data.repository.ContactRepository.receiveAccountDeletion].
 * Nothing about it is shown: the name read off an older version is still the one they had.
 */
@Entity
class PendingProfileKeyEntity(
    @PrimaryKey(autoGenerate = false) val userUuid: String,

    /**
     * The key the last read was made with, the contact's as it then stood, see
     * [ContactEntity.profileEncryptionKey]. A contact this device has a name for is read again
     * only once that changes, a new key of theirs having come in, see `ContactDao.getDueForRead`:
     * read with the same one, it would only say the same again. One it has no name for, the key
     * having opened nothing, is read at every sync all the same, as every contact with no name
     * is, since the owner can store their profile under that very key at any time. Neither read
     * puts back what was tried, see `PendingProfileKeyDao.recordStale`, so asking again is the
     * backoff's to decide either way.
     */
    val profileEncryptionKey: ByteArray,

    /**
     * When this device last tried asking, or null when it has not. A try counts whatever came of
     * it, the server's refusal for either allowance of messages aside.
     */
    val lastRequestedAt: Long?,

    /** How many times this device has tried asking, which the backoff between tries grows with. */
    val requestCount: Int = 0,
)
