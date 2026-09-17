package database.data.repository

import contact.ContactCard
import contact.ContactMessageEnvelope
import contact.ContactRemoval
import contact.ContactSyncError
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.local.entity.ContactEntity
import database.data.remote.AcknowledgeDirectMessagesBody
import database.data.remote.DirectMessageApi
import database.data.remote.MAX_ACKNOWLEDGEMENT_BATCH
import database.data.remote.SendDirectMessageBody
import database.domain.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mls.ApplicationMessageReading
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.isRetryable
import mls.readApplicationMessage
import profile.ProfileRepository
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository

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
     * Records [userUuid] as a contact on this device, [mlsGroupId] being the group the
     * request between the two of them created.
     *
     * [fullName], the name on their profile, is written along when it is already known,
     * and left to [resolveNames] otherwise. The vault only gets them with the next
     * [backUp].
     */
    suspend fun add(
        userUuid: String,
        profileEncryptionKey: ByteArray,
        mlsGroupId: ByteArray,
        fullName: String?,
    ) {
        contactDao.upsert(
            ContactEntity(
                userUuid = userUuid,
                profileEncryptionKey = profileEncryptionKey,
                mlsGroupId = mlsGroupId,
                isBackedUp = false,
            ),
        )

        fullName?.let { saveName(userUuid = userUuid, fullName = it) }
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

        val mlsGroupId = contact.mlsGroupId

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
     * Takes in what contacts sent the user: the answers to the requests the user sent, which
     * make their senders contacts, and the notices of contacts who removed the user, which
     * remove them in turn. Every message is acknowledged once acted on.
     *
     * A message that cannot be read is acknowledged too, since it never will be: typically,
     * the group it was encrypted to lived in a database a log-out has wiped since. One that
     * failed on the user's own storage stays for the next call.
     */
    suspend fun receiveMessages(): EmptyResult<ContactSyncError> {
        val messages = when (val list = directMessageApi.getDirectMessages()) {
            is Result.Success -> list.data.messages
            is Result.Error -> return Result.Error(ContactSyncError.Network(list.error))
        }

        // Most calls find nothing, and opening the MLS client is not free.
        if (messages.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(ContactSyncError.Identity(opened.error))
        }

        val settled = mutableListOf<String>()
        var failure: ContactSyncError? = null

        for (message in messages) {
            val envelope = ContactMessageEnvelope.parse(ciphertext = message.ciphertext)

            if (envelope == null) {
                settled += message.uuid

                continue
            }

            val reading = client.readApplicationMessage(
                mlsGroupId = envelope.mlsGroupId,
                message = envelope.message,
            )

            when (reading) {
                is ApplicationMessageReading.Read -> {
                    receive(client = client, envelope = envelope, reading = reading)

                    settled += message.uuid
                }

                ApplicationMessageReading.Unreadable -> settled += message.uuid

                ApplicationMessageReading.Retry -> failure = failure ?: ContactSyncError.Storage
            }
        }

        // An acknowledgement that does not go through hands the same messages back next
        // time. Reading one again fails, its key is gone, so it is acknowledged then, and
        // what it said is already stored.
        for (batch in settled.chunked(MAX_ACKNOWLEDGEMENT_BATCH)) {
            val acknowledgement = directMessageApi.acknowledgeDirectMessages(
                body = AcknowledgeDirectMessagesBody(messageUuids = batch),
            )

            if (acknowledgement is Result.Error) {
                return Result.Error(ContactSyncError.Network(acknowledgement.error))
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /** Carries to the vault every change it has not taken yet, additions and removals alike. */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = contactDao.getNotBackedUp()

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        val (removed, added) = pending.partition { it.isRemoved }

        val write = vaultRepository.updateContacts(
            added = added.associate { it.userUuid to it.profileEncryptionKey },
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
     */
    suspend fun restore(): EmptyResult<ContactSyncError> {
        val profileEncryptionKeys = when (val read = vaultRepository.getContacts()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(ContactSyncError.Vault(read.error))
        }

        contactDao.insertNew(
            contacts = profileEncryptionKeys.map { (userUuid, profileEncryptionKey) ->
                ContactEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = profileEncryptionKey,
                    // The group's state lived in the database that is gone.
                    mlsGroupId = null,
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
                ciphertext = ContactMessageEnvelope(mlsGroupId = mlsGroupId, message = message).serialize(),
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
     * Acts on a message a contact sent, as MLS authenticated it.
     *
     * A message that is neither a card nor a removal comes from a newer build this one
     * cannot act on, and is dropped like an unreadable one.
     */
    private suspend fun receive(
        client: MlsClient,
        envelope: ContactMessageEnvelope,
        reading: ApplicationMessageReading.Read,
    ) {
        val card = ContactCard.parse(plaintext = reading.payload)

        when {
            card != null -> add(
                userUuid = reading.senderUserUuid,
                profileEncryptionKey = card.profileEncryptionKey,
                mlsGroupId = envelope.mlsGroupId,
                fullName = null,
            )

            ContactRemoval.matches(plaintext = reading.payload) -> {
                forget(userUuid = reading.senderUserUuid, client = client)

                // Whichever group it came in, it has no use left.
                client.deleteGroupQuietly(mlsGroupId = envelope.mlsGroupId)
            }
        }
    }

    /**
     * Removes the contact [userUuid] from this device, leaving a tombstone for [backUp] to
     * carry the removal to the vault, and deletes the group the two of them shared.
     *
     * Their user stays, hidden from the contact list, rather than being deleted: a user can
     * be more than a contact, and a group the two of them are in still lists them.
     */
    private suspend fun forget(userUuid: String, client: MlsClient?) {
        val contact = contactDao.get(userUuid = userUuid) ?: return

        contactDao.markRemoved(userUuid = userUuid)
        userRepository.remove(userUuid = userUuid)

        val mlsGroupId = contact.mlsGroupId ?: return

        client?.deleteGroupQuietly(mlsGroupId = mlsGroupId)
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
