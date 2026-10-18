package contact

import channel.DirectChannelError
import database.data.local.dao.ContactDao
import database.data.local.entity.ContactEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageSender
import message.DirectSendError
import mls.MlsIdentityError
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.ProfileKeyVault

/**
 * Hands the key to the user's own profile to every contact who does not hold it yet.
 *
 * A contact holds the key they were handed last, see [ContactEntity.handedProfileKeyHash]: the
 * one the card that made them a contact carried, or a newer one this handed them since. The
 * key changes when the user removes a contact, see `ProfileRepository.rotateProfileKey`, and
 * the one removed keeps the old one, reading the profile as it was and nothing written after.
 * So would every contact left, and whoever accepts a request that carried a key replaced
 * since, until handed the new one: this is what hands it over, at the end of every backup, see
 * [ContactSync.backUp], so a key that just rotated goes out at once.
 *
 * Who is owed it is told by the hash of the current key the device keeps, see
 * [ProfileKeyVault.getCachedProfileEncryptionKeyPartialHash], so a sync that owes nobody, nearly
 * every one, reads nothing from the vault. One that owes somebody reads the key once.
 *
 * Retried until it lands, or there turns out to be nobody to hand it to, as the keys to
 * one-time expenses are, see `OneTimeExpenseSync.shareOwedKeys`: one message per contact, and
 * the first refusal for the user's allowance of messages stops the pass, the rest waiting for
 * the next one. A key that landed and was lost on the receiving end, unreadable in a group the
 * contact no longer holds, counts as handed here: they ask for it once a read of the profile
 * shows them the key they hold opens no newer version, see [ProfileKeyPull], which is the next
 * time anything is read with it.
 *
 * [send] is [DirectMessageSender.send], or what a test stands in for it: it builds a channel to
 * a contact this device holds none to, one restored from the vault for instance.
 */
class ProfileKeyDelivery internal constructor(
    private val contactDao: ContactDao,
    private val profileKeyVault: ProfileKeyVault,
    private val send: suspend (recipientUserUuid: String, payload: ByteArray) -> EmptyResult<DirectSendError>,
) {
    constructor(
        contactDao: ContactDao,
        profileKeyVault: ProfileKeyVault,
        directMessageSender: DirectMessageSender,
    ) : this(
        contactDao = contactDao,
        profileKeyVault = profileKeyVault,
        // As a plain message, so the contact's phone wakes and syncs, which takes the key in.
        send = { recipientUserUuid, payload ->
            directMessageSender.send(recipientUserUuid = recipientUserUuid, payload = payload)
        },
    )

    /**
     * Serializes [handOut], which every backup reaches, from a screen and a push at once. Each
     * would read the same contacts as owed, and hand them the key twice.
     */
    private val mutex = Mutex()

    /**
     * Hands the current key to every contact owed it, blocked ones aside, recording each one
     * handed it, for the vault to take with the next backup.
     *
     * Every contact owed is attempted, and the first failure is reported, unless the user's
     * allowance of messages runs out, which stops the pass.
     */
    suspend fun handOut(): EmptyResult<ContactSyncError> = mutex.withLock {
        // The key itself, when the hash had to be read from it: whoever is owed is handed it.
        var readKey: ByteArray? = null

        val currentHash = profileKeyVault.getCachedProfileEncryptionKeyPartialHash()
            ?: when (val read = profileKeyVault.getOrCreateProfileEncryptionKey()) {
                is Result.Success -> profileEncryptionKeyPartialHash(read.data).also { readKey = read.data }
                is Result.Error -> return@withLock Result.Error(ContactSyncError.Vault(read.error))
            }

        val owed = contactDao.getUnblocked().filter { it.handedProfileKeyHash != currentHash }

        if (owed.isEmpty()) return@withLock Result.Success(Unit)

        val profileEncryptionKey = readKey
            ?: when (val read = profileKeyVault.getOrCreateProfileEncryptionKey()) {
                is Result.Success -> read.data
                is Result.Error -> return@withLock Result.Error(ContactSyncError.Vault(read.error))
            }

        // What each is recorded as handed is the key that went out, not the one the hash named:
        // a rotation in between leaves them owed the newer one, and the next pass hands it.
        val handedHash = profileEncryptionKeyPartialHash(profileEncryptionKey)
        val payload = ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey).serialize()

        var failure: ContactSyncError? = null

        for (contact in owed) {
            when (val sent = send(contact.userUuid, payload)) {
                is Result.Success -> contactDao.setHandedProfileKeyHash(
                    userUuid = contact.userUuid,
                    handedProfileKeyHash = handedHash,
                )

                is Result.Error -> when (sent.error) {
                    // Nobody to hand the key to: their account is gone, or the server offers no
                    // way to reach them that this build can use. If they come back, they ask for
                    // it, see ProfileKeyPull.
                    DirectSendError.RecipientGone,
                    DirectSendError.Channel(DirectChannelError.Unreachable),
                        -> contactDao.setHandedProfileKeyHash(
                        userUuid = contact.userUuid,
                        handedProfileKeyHash = handedHash,
                    )

                    // Somebody this device no longer has pinned, or whose identity is in doubt.
                    // No retry changes either, so the key waits until that does.
                    DirectSendError.Channel(DirectChannelError.NotPinned),
                    DirectSendError.Channel(DirectChannelError.IdentityChanged),
                        -> Unit

                    DirectSendError.RateLimited -> return@withLock Result.Error(
                        ContactSyncError.Network(NetworkError.TOO_MANY_REQUESTS),
                    )

                    else -> failure = failure ?: sent.error.toContactSyncError()
                }
            }
        }

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}

/** The same failure, told the way a contact sync tells it. */
internal fun DirectSendError.toContactSyncError(): ContactSyncError =
    when (this) {
        is DirectSendError.Channel -> when (val channelError = error) {
            is DirectChannelError.Identity -> ContactSyncError.Identity(channelError.error)
            DirectChannelError.NoSession -> ContactSyncError.Identity(MlsIdentityError.MissingSession)
            is DirectChannelError.Network -> ContactSyncError.Network(channelError.error)
            else -> ContactSyncError.Storage
        }

        DirectSendError.Storage, DirectSendError.GroupUnusable -> ContactSyncError.Storage

        is DirectSendError.Network -> ContactSyncError.Network(error)

        DirectSendError.RecipientGone, DirectSendError.RateLimited -> ContactSyncError.Storage
    }
