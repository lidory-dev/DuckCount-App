package contact

import database.data.local.dao.ContactDao
import database.data.local.entity.VerifiableContact
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.safetyNumberWith
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.Result

/**
 * The safety numbers the user compares with their contacts to tell that nobody stands between
 * them, and the ones they marked verified.
 *
 * Everything sent to a contact is only as private as the identity this device pinned for them,
 * which came from whatever key package or Welcome the server handed over when the two of them
 * became contacts, see [ContactRequestRepository][database.data.repository.ContactRequestRepository]:
 * that it is theirs is the server's word. A server that handed each of them a key of its own
 * instead would read everything one sends the other and pass it on, a man in the middle neither
 * of them could tell from the real thing.
 *
 * A safety number settles it. It is made of the user's own identity and the one pinned for the
 * contact, never of anything the server serves, and both phones show the same one, see
 * [MlsClient.safetyNumber]: when the two of them read theirs out to each other, in person or on a
 * call they trust, and they match, each of them pinned the other's real identity.
 *
 * The user's mark is the number they compared, kept with the contact, see
 * [ContactEntity.verifiedSafetyNumber][database.data.local.entity.ContactEntity.verifiedSafetyNumber],
 * so the next [ContactSync.backUp] carries it to the vault, and it comes back with the contact
 * after a log-out or a reinstall. It counts only while it is the number computed now, see
 * [observe].
 *
 * [client] is [MlsIdentityRepository.client], which a test stands in for.
 */
class SafetyNumbers internal constructor(
    private val contactDao: ContactDao,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
) {
    constructor(contactDao: ContactDao, mlsIdentityRepository: MlsIdentityRepository) : this(
        contactDao = contactDao,
        client = { mlsIdentityRepository.client() },
    )

    /**
     * Where the user stands with the contact [userUuid], again whenever that changes.
     *
     * They are verified while the number computed now is the one the user marked, so a mark made
     * for identities other than the ones pinned now stops counting on its own. A blocked contact
     * shows no number at all, see [ContactVerification.IdentityChanged].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(userUuid: String): Flow<ContactVerification> =
        contactDao.getVerifiableAsFlow(userUuid = userUuid)
            // The table changes with every contact, and computing the number reads the store.
            .distinctUntilChanged()
            .mapLatest { contact -> verificationOf(userUuid = userUuid, contact = contact) }

    /**
     * Marks [safetyNumber], the one the user compared with the contact [userUuid], verified, for
     * the next [ContactSync.backUp] to carry to the vault.
     *
     * The number compared is the one kept, rather than one computed again here: whatever number
     * the screen showed, it is the one the user vouches for, and it only counts while it is the one
     * computed, see [observe]. Nobody else's: one who is not, or no longer, a contact is left
     * alone.
     */
    suspend fun markVerified(userUuid: String, safetyNumber: String) {
        contactDao.setVerifiedSafetyNumber(userUuid = userUuid, verifiedSafetyNumber = safetyNumber)
    }

    /** Clears the mark [markVerified] made for the contact [userUuid], for the vault to take in turn. */
    suspend fun clearMark(userUuid: String) {
        contactDao.setVerifiedSafetyNumber(userUuid = userUuid, verifiedSafetyNumber = null)
    }

    private suspend fun verificationOf(userUuid: String, contact: VerifiableContact?): ContactVerification {
        contact ?: return ContactVerification.NotContact

        if (contact.isBlocked) return ContactVerification.IdentityChanged

        val opened = when (val opening = client()) {
            is Result.Success -> opening.data
            is Result.Error -> return ContactVerification.Unavailable(ContactVerificationError.Identity(opening.error))
        }

        val safetyNumber = try {
            opened.safetyNumberWith(peerUuid = userUuid)
        } catch (_: MlsException) {
            return ContactVerification.Unavailable(ContactVerificationError.Storage)
        }
            // Every contact is pinned before they become one, by the request that made them one
            // or by the vault that brought them back, and only a log-out takes a pin away, in the
            // transaction that empties the contacts: one that came after the row was read.
            ?: return ContactVerification.NotContact

        return ContactVerification.Available(
            safetyNumber = safetyNumber,
            isVerified = safetyNumber == contact.verifiedSafetyNumber,
        )
    }
}
