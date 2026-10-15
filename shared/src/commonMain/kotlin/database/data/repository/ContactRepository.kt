package database.data.repository

import authenticationUtils.clearContactsToRestore
import authenticationUtils.hasContactsToRestore
import contact.ContactRemoval
import contact.ContactSyncError
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.entity.ContactEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.IdentityConflictEntity
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
import profile.ProfileError
import profile.ProfileRepository
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import mls.pinnedSignatureKeyFor
import mls.restorePin
import mls.withPeerLock
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
    private val identityConflictDao: IdentityConflictDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
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

    /**
     * Serializes [restore], which the message sync and the contact requests can all reach at
     * once right after a login. A second caller waits for the first one's contacts rather
     * than reading the vault again and pinning from a read of its own.
     */
    private val restoreMutex = Mutex()

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
     *
     * The vault never takes a pin over the one it already holds for a contact, see
     * [ContactBook.with][vault.ContactBook.with]. A contact it pins somebody else for is
     * blocked instead, see [IdentityConflictEntity], and is not written again.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = contactDao.getNotBackedUp()

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        val (removed, added) = pending.partition { it.isRemoved }

        // Nothing is written without it: a contact marked backed up without the pin this
        // device holds for them would never be written again.
        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Identity(opened.error))
        }

        // Nor without the pins themselves, for the same reason: one that could not be read
        // is not the same as none.
        val contacts = try {
            added.associate { contact ->
                contact.userUuid to VaultContact(
                    profileEncryptionKey = contact.profileEncryptionKey,
                    // Null for a contact this device has never reached over MLS, which
                    // leaves whatever another device recorded for them alone.
                    mlsSignaturePublicKey = client.pinnedSignatureKeyFor(peerUuid = contact.userUuid),
                )
            }
        } catch (_: MlsException) {
            return@withLock Result.Error(ContactSyncError.Storage)
        }

        val update = vaultRepository.updateContacts(
            added = contacts,
            removed = removed.map { it.userUuid },
        )

        val conflicting = when (update) {
            is Result.Success -> update.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Vault(update.error))
        }

        conflicting.forEach { identityConflictDao.insert(IdentityConflictEntity(peerUuid = it)) }

        // Both only touch rows still in the state they were read in, so a contact removed
        // or added back during the write stays pending for the next one.
        contactDao.markBackedUp(userUuids = added.map { it.userUuid })
        contactDao.deleteRemoved(userUuids = removed.map { it.userUuid })

        Result.Success(Unit)
    }

    /**
     * Reads the name on the profile of every contact this device has none for.
     *
     * Every contact is attempted, and the first failure is reported. Only a profile that
     * could not be asked for is one, see [ContactSyncError.Profile].
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

                // One contact's profile failing says nothing about the next one's. And
                // one gone with its owner's account stays gone: not a failure, or every
                // sync would report it from then on.
                is Result.Error -> if (read.error is ProfileError.Network) {
                    failure = failure ?: ContactSyncError.Profile(read.error)
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Brings back the contacts the vault holds, which a log-out or a reinstall took off
     * this device, then reads their names. Does nothing once they are back, see
     * [authenticationUtils.setContactsToRestore].
     *
     * No channel comes back with them: the group's state lived in the database that is
     * gone, and no Welcome can reach a key package whose secret went with it. The first
     * message to a restored contact builds a new group instead.
     *
     * The identities pinned for them go back into the MLS client first, from the same read
     * as the contacts themselves. The client puts them back when it opens too, see
     * [MlsIdentityRepository], but from a read of its own: a server showing the two reads
     * different books would otherwise bring contacts back without their pins, and the next
     * Welcome naming one of them would pin whatever identity it carries.
     *
     * A contact whose pin disagrees with the one this device already holds comes back
     * blocked, see [IdentityConflictEntity]: the device's pin stays, a pin is never replaced,
     * and the contact would otherwise be reached through an identity the vault does not
     * vouch for.
     *
     * Whatever needs to know who the contacts are runs this first: reading the messages, see
     * [DirectMessageSync][message.DirectMessageSync], and sending or accepting a contact
     * request, see [ContactRequestRepository], which would otherwise pin one of them from
     * whatever identity the server hands over.
     *
     * A name that could not be read is not reported: the next [ContactSync][contact.ContactSync]
     * reads it again.
     */
    suspend fun restore(): EmptyResult<ContactSyncError> {
        val restored = restoreMutex.withLock {
            if (!hasContactsToRestore()) return Result.Success(Unit)

            bringBack()
        }

        // Outside the lock: whoever waits on it needs the contacts and their pins, not their
        // names.
        if (restored is Result.Success) resolveNames()

        return restored
    }

    /** Puts back the contacts the vault holds, and their pins, for [restore]. */
    private suspend fun bringBack(): EmptyResult<ContactSyncError> {
        val contacts = when (val read = vaultRepository.getContacts()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(ContactSyncError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(ContactSyncError.Identity(opened.error))
        }

        try {
            contacts.forEach { (userUuid, contact) ->
                val signatureKey = contact.mlsSignaturePublicKey ?: return@forEach

                if (!client.restorePin(peerUuid = userUuid, signatureKey = signatureKey)) {
                    identityConflictDao.insert(IdentityConflictEntity(peerUuid = userUuid))
                }
            }
        } catch (_: MlsException) {
            return Result.Error(ContactSyncError.Storage)
        }

        contactDao.insertNew(
            contacts = contacts.map { (userUuid, contact) ->
                ContactEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = contact.profileEncryptionKey,
                    isBackedUp = true,
                )
            },
        )

        clearContactsToRestore()

        return Result.Success(Unit)
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
     *
     * What this device is still waiting for from them is noted first: the keys to those
     * expenses are the only ones they can still hand over, see
     * [PendingExpenseKeyEntity.isAwaitedAtRemoval][database.data.local.entity.PendingExpenseKeyEntity.isAwaitedAtRemoval].
     */
    private suspend fun forget(userUuid: String, client: MlsClient?) {
        contactDao.get(userUuid = userUuid) ?: return

        // First, so that there is no moment when they are no longer a contact and what they
        // owe is not noted yet: a key of theirs read then would be dropped. And only once they
        // are known to be one: a notice of removal from anybody else would make whatever they
        // put the user on owed.
        pendingExpenseKeyDao.markAwaitedAtRemoval(creditorUuid = userUuid)

        contactDao.markRemoved(userUuid = userUuid)
        userRepository.remove(userUuid = userUuid)

        // Under the peer's lock, which every change to the groups their channel names takes: a
        // group adopted between the read and the clear would be left with nothing naming it.
        withPeerLock(peerUuid = userUuid) {
            val channel = directChannelDao.get(peerUuid = userUuid)

            // The channel row stays, emptied: the two of them may still owe each other money,
            // and a one-time expense they share is opened with a key only the other one has.
            // What goes is the MLS state, which has no use until a new group is built.
            directChannelDao.clear(peerUuid = userUuid)

            val held = listOfNotNull(channel?.mlsGroupId, channel?.previousMlsGroupId, channel?.olderMlsGroupId)

            // A caller that found no group had no client to pass, and one may have been adopted
            // since it looked.
            val deleting = client
                ?: held.takeIf { it.isNotEmpty() }?.let { (mlsIdentityRepository.client() as? Result.Success)?.data }

            held.forEach { deleting?.deleteGroupQuietly(mlsGroupId = it) }
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
