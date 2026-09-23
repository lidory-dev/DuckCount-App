package database.data.repository

import contact.ContactCard
import contact.ContactRemoval
import contact.ContactSyncError
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.local.dao.DirectChannelDao
import database.data.local.entity.ContactEntity
import database.data.local.entity.DirectChannelEntity
import database.data.remote.DirectMessageApi
import database.data.remote.SendDirectMessageBody
import database.domain.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageEnvelope
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.isRetryable
import profile.ProfileRepository
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import mls.restorePin
import mls.pinnedSignatureKeyFor
import vault.VaultContact

/**
 * The user's contacts: the people whose request they accepted, and the ones who accepted
 * theirs.
 *
 * The vault is where contacts are kept, since the server holds no record of them.
 * This device keeps a copy, and their names in [UserRepository], which is what the contact
 * list shows.
 *
 * Every change lands on the device first, and [backUp] carries it to the vault.
 */
class ContactRepository(
    private val contactDao: ContactDao,
    private val directChannelDao: DirectChannelDao,
    private val userRepository: UserRepository,
    private val vaultRepository: VaultRepository,
    private val profileRepository: ProfileRepository,
    private val directMessageApi: DirectMessageApi,
    private val mlsIdentityRepository: MlsIdentityRepository,
) {
    /**
     * Serializes [backUp]. The contacts are sealed whole, so two writes from this device
     * would conflict, and the second one would fail for nothing.
     */
    private val backUpMutex = Mutex()

    /** Whether [userUuid] is one of the user's contacts. */
    suspend fun isContact(userUuid: String): Boolean {
        return contactDao.get(userUuid = userUuid) != null
    }

    /**
     * Records [userUuid] as a contact on this device.
     *
     * [fullName], the name on their profile, is written along when it is already known,
     * and left to [resolveNames] otherwise. The vault only gets them with the next
     * [backUp].
     *
     * The group the two of them talk through is recorded separately, see
     * [database.data.local.entity.DirectChannelEntity]: a channel outlives the contact, and
     * whoever knows which group it is records it, either the accept that joined it or the
     * sync that read a message in it.
     */
    suspend fun add(
        userUuid: String,
        profileEncryptionKey: ByteArray,
        fullName: String?,
    ) {
        contactDao.upsert(
            ContactEntity(
                userUuid = userUuid,
                profileEncryptionKey = profileEncryptionKey,
                isBackedUp = false,
            ),
        )

        fullName?.let { saveName(userUuid = userUuid, fullName = it) }
    }

    /**
     * Records the contact card [senderUserUuid] sent, which is how the user learns that a
     * request they sent was accepted.
     */
    internal suspend fun receiveCard(senderUserUuid: String, card: ContactCard) {
        add(
            userUuid = senderUserUuid,
            profileEncryptionKey = card.profileEncryptionKey,
            fullName = null,
        )
    }

    /** Acts on [senderUserUuid]'s notice that they removed the user from their contacts. */
    internal suspend fun receiveRemoval(senderUserUuid: String) {
        val client = (mlsIdentityRepository.client() as? Result.Success)?.data

        forget(userUuid = senderUserUuid, client = client)
    }

    /**
     * Removes the contact [userUuid], telling them first so their client removes the user
     * in turn.
     *
     * The notice travels in the group the request between the two of them created, so it
     * can only be sent while this device still holds it: a contact restored from the vault
     * after a log-out or a reinstall is removed without one, and keeps the user. Nothing is
     * removed while the notice has yet to go out, so a failure can simply be retried. The
     * vault only drops the contact with the next [backUp].
     */
    suspend fun remove(userUuid: String): EmptyResult<RemoveContactError> {
        // Not a contact any more, which is all removing them was for.
        val contact = contactDao.get(userUuid = userUuid) ?: return Result.Success(Unit)

        val mlsGroupId = directChannelDao.get(peerUuid = userUuid)?.mlsGroupId

        if (mlsGroupId == null) {
            forget(userUuid = userUuid, client = null)

            return Result.Success(Unit)
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(RemoveContactError.Identity(opened.error))
        }

        val notice = sendRemovalNotice(client = client, userUuid = userUuid, mlsGroupId = mlsGroupId)

        if (notice is Result.Error) return notice

        forget(userUuid = userUuid, client = client)

        return Result.Success(Unit)
    }

    /**
     * Carries to the vault every change it has not taken yet, additions and removals alike.
     *
     * The MLS identity pinned for each contact goes along with them. Pins live in the local
     * database, which a log-out empties, and a device that came back without them would
     * accept whichever key package the server served next for each contact and pin that,
     * which is the substitution pinning exists to catch.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = contactDao.getNotBackedUp()

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        val (removed, added) = pending.partition { it.isRemoved }

        val client = (mlsIdentityRepository.client() as? Result.Success)?.data

        val write = vaultRepository.updateContacts(
            added = added.associate { contact ->
                contact.userUuid to VaultContact(
                    profileEncryptionKey = contact.profileEncryptionKey,
                    // Null for a contact this device has never reached over MLS, which
                    // leaves whatever another device recorded for them alone.
                    mlsSignaturePublicKey = client?.pinnedSignatureKeyFor(peerUuid = contact.userUuid),
                )
            },
            removed = removed.map { it.userUuid },
        )

        if (write is Result.Error) return@withLock Result.Error(ContactSyncError.Vault(write.error))

        // Both only touch rows still in the state they were read in, so a contact removed
        // or added back during the write stays pending for the next one.
        contactDao.markBackedUp(userUuids = added.map { it.userUuid })
        contactDao.deleteRemoved(userUuids = removed.map { it.userUuid })

        Result.Success(Unit)
    }

    /**
     * Reads the name on the profile of every contact this device has none for.
     *
     * Every contact is attempted, and the first failure is reported.
     */
    suspend fun resolveNames(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        for (contact in contactDao.getWithoutName()) {
            val read = profileRepository.getFullName(
                userUuid = contact.userUuid,
                profileEncryptionKey = contact.profileEncryptionKey,
            )

            when (read) {
                is Result.Success -> saveName(userUuid = contact.userUuid, fullName = read.data)

                // One contact's profile failing, gone with its owner's account for
                // instance, says nothing about the next one's.
                is Result.Error -> failure = failure ?: ContactSyncError.Profile(read.error)
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Brings back the contacts the vault holds, which a log-out or a reinstall took off
     * this device, then reads their names.
     *
     * No channel comes back with them: the group's state lived in the database that is
     * gone, and no Welcome can reach a key package whose secret went with it. The first
     * message to a restored contact builds a new group instead.
     *
     * Their pinned MLS identities do come back, and go back into the MLS client *before*
     * the rows are visible to anything that would build such a group. Building one fetches
     * a key package from the server and pins whatever identity it carries, so a client that
     * had forgotten its pins would let the server decide who each contact is, which is the
     * one thing pinning is for.
     */
    suspend fun restore(): EmptyResult<ContactSyncError> {
        val contacts = when (val read = vaultRepository.getContacts()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(ContactSyncError.Vault(read.error))
        }

        restorePins(contacts = contacts)

        contactDao.insertNew(
            contacts = contacts.map { (userUuid, contact) ->
                ContactEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = contact.profileEncryptionKey,
                    isBackedUp = true,
                )
            },
        )

        return resolveNames()
    }

    /** Tells the contact [userUuid], in the group [mlsGroupId], that the user removed them. */
    private suspend fun sendRemovalNotice(
        client: MlsClient,
        userUuid: String,
        mlsGroupId: ByteArray,
    ): EmptyResult<RemoveContactError> {
        val message = try {
            client.encryptApplicationMessage(
                mlsGroupId = mlsGroupId,
                payload = ContactRemoval.serialize(),
            )
        } catch (exception: MlsException) {
            if (exception.isRetryable()) return Result.Error(RemoveContactError.Storage)

            // The group is unusable for good, so there is no way left to tell them, and
            // refusing to remove the contact would keep them forever.
            return Result.Success(Unit)
        }

        val send = directMessageApi.sendDirectMessage(
            body = SendDirectMessageBody(
                recipientUserUuid = userUuid,
                ciphertext = DirectMessageEnvelope(mlsGroupId = mlsGroupId, message = message).serialize(),
            ),
        )

        return when (send) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> when (send.error) {
                // Their account is gone, and there is nobody left to tell.
                NetworkError.NOT_FOUND -> Result.Success(Unit)

                NetworkError.TOO_MANY_REQUESTS -> Result.Error(RemoveContactError.RateLimited)

                else -> Result.Error(RemoveContactError.Network(send.error))
            }
        }
    }

    /**
     * Removes the contact [userUuid] from this device, leaving a tombstone for [backUp] to
     * carry the removal to the vault, and deletes the groups the two of them shared.
     *
     * Their user stays, hidden from the contact list, rather than being deleted: a user can
     * be more than a contact, and a group the two of them are in still lists them. Their
     * channel row stays too, emptied, see [DirectChannelEntity].
     */
    private suspend fun forget(userUuid: String, client: MlsClient?) {
        contactDao.get(userUuid = userUuid) ?: return

        contactDao.markRemoved(userUuid = userUuid)
        userRepository.remove(userUuid = userUuid)

        val channel = directChannelDao.get(peerUuid = userUuid)

        // The channel row stays, emptied: the two of them may still owe each other money,
        // and a one-time expense they share is opened with a key only the other one has.
        // What goes is the MLS state, which has no use until a new group is built.
        directChannelDao.clear(peerUuid = userUuid)

        listOfNotNull(channel?.mlsGroupId, channel?.previousMlsGroupId).forEach {
            client?.deleteGroupQuietly(mlsGroupId = it)
        }
    }

    /**
     * Puts back the MLS identities the vault holds for [contacts].
     *
     * Best effort by design. A pin that cannot be restored leaves this device where it was
     * before pins were backed up at all, which is worse than having it but is not a reason
     * to refuse to restore the contacts themselves.
     */
    private suspend fun restorePins(contacts: Map<String, VaultContact>) {
        val backedUp = contacts.mapNotNull { (userUuid, contact) ->
            contact.mlsSignaturePublicKey?.let { userUuid to it }
        }

        if (backedUp.isEmpty()) return

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return
        }

        backedUp.forEach { (userUuid, signatureKey) ->
            client.restorePin(peerUuid = userUuid, signatureKey = signatureKey)
        }
    }

    private suspend fun saveName(userUuid: String, fullName: String) {
        userRepository.insert(
            User(
                uuid = userUuid,
                // Only the user's own is known, a contact's discovery handle never reaches
                // this device.
                username = "",
                fullName = fullName,
            ),
        )
    }
}
