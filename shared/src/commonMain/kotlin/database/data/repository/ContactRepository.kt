package database.data.repository

import authenticationUtils.clearContactsToRestore
import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.hasContactsToRestore
import contact.AccountDeletionNotice
import contact.ContactRemoval
import contact.ContactSyncError
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.dao.PendingProfileKeyDao
import database.data.local.entity.ContactEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.remote.DirectMessageApi
import database.data.remote.SendDirectMessageBody
import database.data.remote.SendDirectMessageError
import database.domain.User
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import message.DirectMessageEnvelope
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.isRetryable
import profile.ProfileError
import profile.ProfileRepository
import push.WakeList
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.asEmptyDataResult
import vault.VaultRepository
import mls.pinnedSignatureKeyFor
import mls.restorePin
import mls.withPeerLock
import vault.ContactBook
import vault.VaultContact

/**
 * The user's contacts: the people whose request they accepted, and the ones who accepted
 * theirs.
 *
 * The vault is where contacts are kept, since the server holds no record of them.
 * This device keeps a copy, and their names in [UserRepository], which is what the contact
 * list shows. Of a contact who deleted their account, the vault keeps nothing but their name,
 * see [receiveAccountDeletion].
 *
 * Every change lands on the device first, and [backUp] carries it to the vault.
 *
 * Removing a contact also replaces the key to the user's own profile, which the one removed
 * holds, see [ContactEntity.rotatesProfileKey], and every contact left is handed the new one,
 * see [ProfileKeyDelivery][contact.ProfileKeyDelivery]. A contact's own key, handed over the
 * same way, can go missing on the way, and reading their profile is what tells, see
 * [resolveNames].
 */
class ContactRepository(
    private val contactDao: ContactDao,
    private val directChannelDao: DirectChannelDao,
    private val identityConflictDao: IdentityConflictDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val pendingProfileKeyDao: PendingProfileKeyDao,
    private val userRepository: UserRepository,
    private val vaultRepository: VaultRepository,
    private val profileRepository: ProfileRepository,
    private val directMessageApi: DirectMessageApi,
    private val mlsIdentityRepository: MlsIdentityRepository,
    private val wakeList: WakeList,
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
     * and left to [resolveNames] otherwise. [handedProfileKeyHash] is the user's own key the
     * card that made them a contact carried, see [ContactEntity.handedProfileKeyHash]. The
     * vault only gets them with the next [backUp]. They may wake the user from here on, see
     * [WakeList]. One who still is a contact keeps the safety number the user verified with
     * them, see [ContactDao.add].
     *
     * The group the two of them talk through is recorded separately, see
     * [database.data.local.entity.DirectChannelEntity]: a channel outlives the contact, and
     * whoever knows which group it is records it, the acceptance that made them a contact,
     * the user's or theirs, and after that the sync that reads them in another group.
     */
    suspend fun add(
        userUuid: String,
        profileEncryptionKey: ByteArray,
        handedProfileKeyHash: String?,
        fullName: String?,
    ) {
        contactDao.add(
            userUuid = userUuid,
            profileEncryptionKey = profileEncryptionKey,
            handedProfileKeyHash = handedProfileKeyHash,
        )

        fullName?.let { saveName(userUuid = userUuid, fullName = it) }

        wakeList.rebuild()
    }

    /**
     * Acts on [senderUserUuid]'s notice that they removed the user from their contacts.
     *
     * The user's profile key stays as it is, see [ContactEntity.rotatesProfileKey]: it only
     * ever rotates on the user's own action.
     *
     * The client that deletes the groups the two of them shared is opened only if there is one,
     * see [forget], already open by then: the notice was just read with it.
     */
    internal suspend fun receiveRemoval(senderUserUuid: String) {
        forget(userUuid = senderUserUuid, client = null, rotatesProfileKey = false, isAccountDeleted = false)
    }

    /**
     * Acts on [userUuid] having deleted their account: a contact is forgotten as if their notice of
     * removal had come in, see [receiveRemoval], but for the name this device shows for them, which
     * the next [backUp] keeps in the vault in place of their entry, see
     * [ContactEntity.isAccountDeleted]: the server deletes their profile with the account. Anybody
     * else is left alone, so that no stranger can have this device keep or drop anything.
     *
     * This device learns it one of three ways. Their app tells every contact it holds a group with
     * as the account goes, see [AccountDeletionNotice], read by
     * [ContactMessageHandler][contact.ContactMessageHandler]. That reaches only those, and can go
     * missing on the way, while the server tells nobody of a deletion: the others find out from
     * the `410` the server answers once this device names them. A message addressed to them comes
     * back with one, see [SendDirectMessageError.RecipientDeleted], the first time this device
     * sends them anything after in a group it holds with them. Every message goes through
     * [message.DirectMessageSender], which brings it here, but the notices this repository posts
     * itself: [remove] acts on the `410` to its own the same way, see [RemovalNotice], and
     * [sendNotice] leaves them as they are, see there. A one-time expense the user creates naming
     * them, or edits to add them, comes back with one too, and takes no group: the server refuses
     * it, naming them, see [ExpenseRepository.createOneTimeExpense], and the refusal is brought
     * here through [message.DirectMessageSender.receiveAccountDeletions].
     *
     * Either `410` is brought here only once nothing they sent is left to read, which opens only
     * in the groups forgetting them deletes, see [message.DirectMessageSender]. With no group, a
     * message asks first for the key package a new one needs, which the server answers for a
     * deleted account as for one that published none, see
     * [DirectChannelError.Unreachable][channel.DirectChannelError.Unreachable], and they stay a
     * contact until the user names them on a new one-time expense or adds them to one. What else
     * they were owed is the sender's to settle.
     *
     * Their key to the user's profile is not rotated away, as for any notice of removal, see
     * [ContactEntity.rotatesProfileKey]: it only ever rotates on the user's own action. Their
     * account need not even be gone: their notice goes out ahead of the deletion, which can still
     * fail and leave the account, its session and the key they hold as they were, see
     * [account.AccountDeletion].
     *
     * Not cancellable: forgotten halfway, they would be no contact any more, and so never forgotten
     * again, while still on the wake list, see [WakeList], and in the groups the two of them shared.
     */
    internal suspend fun receiveAccountDeletion(userUuid: String) {
        withContext(NonCancellable) {
            forget(userUuid = userUuid, client = null, rotatesProfileKey = false, isAccountDeleted = true)
        }
    }

    /**
     * Takes [profileEncryptionKey] as the new key to the profile of [senderUserUuid], from
     * their [ProfileKeyUpdate][contact.ProfileKeyUpdate], pushed as it rotated or answering
     * this device's request for it. The vault only gets it with the next [backUp].
     *
     * Only from a contact: anybody else is handing over the key to a profile this device has
     * no business reading, and a former contact one it no longer reads. The name shown stays
     * as it is, and [resolveNames] reads it with the new key whenever this device has none, or
     * was missing their current key, which it reads to find out whether this is the one.
     *
     * The very key this device holds is the contact saying it is their current one, so there
     * is nothing newer to ask them for, whatever the version it opens says: one of theirs can
     * be newer and under a key they never held, a rotation of theirs cut short by a log-out
     * having left it, see `ProfileRepository.rotateProfileKey`. Asked again, they would only
     * answer the same, every day.
     */
    internal suspend fun receiveProfileKey(senderUserUuid: String, profileEncryptionKey: ByteArray) {
        val contact = contactDao.get(userUuid = senderUserUuid) ?: return

        if (contact.profileEncryptionKey.contentEquals(profileEncryptionKey)) {
            pendingProfileKeyDao.delete(userUuid = senderUserUuid)

            return
        }

        contactDao.replaceProfileEncryptionKey(
            userUuid = senderUserUuid,
            profileEncryptionKey = profileEncryptionKey,
        )
    }

    /**
     * Removes the contact [userUuid], telling them first so their client removes the user
     * in turn.
     *
     * The notice travels in the group the request between the two of them created, so it
     * can only be sent while this device still holds it: a contact restored from the vault
     * after a log-out or a reinstall is removed without one, and keeps the user, as is one the
     * server answers it has no way to reach, see [toRemovalNotice]. Otherwise nothing is
     * removed while the notice has yet to go out, so a failure can simply be retried.
     *
     * Either way, they hold the key to the user's profile, which is owed a rotation from then on,
     * see [ProfileRepository.oweProfileKeyRotation], done by the next [backUp] before the vault
     * drops them, see [ContactEntity.rotatesProfileKey], or by an edit of the profile made first:
     * they then read the profile as it is now, and nothing the user writes after. Unless the notice
     * finds they deleted their account, which forgets them as [receiveAccountDeletion] does.
     *
     * The rotation is owed before the notice goes out, see
     * [ProfileRepository.oweProfileKeyRotation] on why, under a lock that waits for any rotation
     * of the key or edit of the profile under way and holds off any other until the removal is
     * written down. A device that locked during that wait cannot keep the key owed, and nothing
     * is done, see [RemoveContactError.DeviceLocked]. Not cancellable once the notice is out: the
     * contact drops the user as they read it, and nothing would ever tell this device to follow.
     */
    suspend fun remove(userUuid: String): EmptyResult<RemoveContactError> {
        // Not a contact any more, which is all removing them was for.
        contactDao.get(userUuid = userUuid) ?: return Result.Success(Unit)

        return profileRepository.oweProfileKeyRotation { tellAndForget(userUuid = userUuid) }
            ?: Result.Error(RemoveContactError.DeviceLocked)
    }

    /**
     * Tells the contact [userUuid] that the user removed them, when this device holds a group to
     * tell them in, then forgets them, for [remove], once the rotation is owed.
     */
    private suspend fun tellAndForget(userUuid: String): EmptyResult<RemoveContactError> {
        val mlsGroupId = directChannelDao.get(peerUuid = userUuid)?.mlsGroupId

        if (mlsGroupId == null) {
            forget(userUuid = userUuid, client = null, rotatesProfileKey = true, isAccountDeleted = false)

            return Result.Success(Unit)
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(RemoveContactError.Identity(opened.error))
        }

        val sent = postNotice(
            client = client,
            userUuid = userUuid,
            mlsGroupId = mlsGroupId,
            notice = ContactRemoval.serialize(),
        )

        val notice = when (sent) {
            is Result.Success -> sent.data
            is Result.Error -> return sent
        }

        withContext(NonCancellable) {
            // One who turns out to have deleted their account is forgotten as any contact who did
            // is, see [receiveAccountDeletion], owing the key no rotation and leaving their name.
            // The new key the removal already minted stays owed all the same, which costs one
            // rotation more, at the next edit, see [ProfileRepository.oweProfileKeyRotation].
            forget(
                userUuid = userUuid,
                client = client,
                rotatesProfileKey = notice.rotatesProfileKey,
                isAccountDeleted = notice.isAccountDeleted,
            )
        }

        return Result.Success(Unit)
    }

    /**
     * Tells the contact [userUuid] what [notice] says, for an account deletion, see
     * [account.AccountDeletion], whose [AccountDeletionNotice] it is: their app forgets the user
     * and keeps their name, see [receiveAccountDeletion], the server telling nobody of a deletion.
     *
     * Nothing more. They are not forgotten, nor is a rotation of the key to the user's profile
     * owed, as [remove] would: the deletion takes the profile with the account, and ending the
     * session that follows wipes the contacts off this device.
     *
     * Only in the group this device holds with them, as for [remove]. With none, there is nothing
     * to tell them in, and nothing is sent. Nor is anybody told who deleted their own account,
     * and they are not forgotten either, see [receiveAccountDeletion]: should the user's deletion
     * then fail, the next message this device sends them finds it out again.
     */
    suspend fun sendNotice(userUuid: String, notice: ByteArray): EmptyResult<RemoveContactError> {
        val mlsGroupId = directChannelDao.get(peerUuid = userUuid)?.mlsGroupId ?: return Result.Success(Unit)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(RemoveContactError.Identity(opened.error))
        }

        return postNotice(client = client, userUuid = userUuid, mlsGroupId = mlsGroupId, notice = notice)
            .asEmptyDataResult()
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
     *
     * A contact the user removed reaches the vault only once the key to the user's profile,
     * which they hold, has rotated, see [ContactEntity.rotatesProfileKey]: one rotation for
     * every such removal read, ahead of the write. When it fails, the rest is written all the
     * same, those removals wait on the device for the next attempt, and the failure is
     * reported.
     *
     * A contact who deleted their account waits for nothing, and leaves in the vault nothing but
     * the name this device shows for them, their key and their pin going, see
     * [ContactEntity.isAccountDeleted]. One it never read a name for simply goes, as any removal
     * does: there is nothing else to keep of them.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = PendingBackUp(contacts = contactDao.getNotBackedUp())

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        // Nothing is written without it: a contact marked backed up without the pin this
        // device holds for them would never be written again.
        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Identity(opened.error))
        }

        val rotation = pending.rotating.takeIf { it.isNotEmpty() }?.let { rotateProfileKey(rotating = it) }

        val isProfileKeyRotated = rotation !is Result.Error

        val removed = pending.removals(isProfileKeyRotated = isProfileKeyRotated)

        // Reported once whatever does not wait for it is written.
        val failure = (rotation as? Result.Error)?.error

        if (!pending.writesAnything(isProfileKeyRotated = isProfileKeyRotated)) {
            return@withLock failure?.let { Result.Error(it) } ?: Result.Success(Unit)
        }

        // Nor without the pins themselves, for the same reason: one that could not be read
        // is not the same as none.
        val contacts = try {
            pending.added.associate { contact ->
                contact.userUuid to VaultContact(
                    profileEncryptionKey = contact.profileEncryptionKey,
                    // Null for a contact this device has never reached over MLS, which
                    // leaves whatever another device recorded for them alone.
                    mlsSignaturePublicKey = client.pinnedSignatureKeyFor(peerUuid = contact.userUuid),
                    handedProfileKeyHash = contact.handedProfileKeyHash,
                    verifiedSafetyNumber = contact.verifiedSafetyNumber,
                )
            }
        } catch (_: MlsException) {
            return@withLock Result.Error(ContactSyncError.Storage)
        }

        val update = vaultRepository.updateContacts(
            added = contacts,
            removed = removed.map { it.userUuid },
            deletedAccounts = namesKept(pending = pending),
        )

        val conflicting = when (update) {
            is Result.Success -> update.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Vault(update.error))
        }

        conflicting.forEach { identityConflictDao.insert(IdentityConflictEntity(peerUuid = it)) }

        // A contact blocked must not wake the user a moment longer.
        if (conflicting.isNotEmpty()) wakeList.rebuild()

        // Both only touch rows still in the state they were read in, so a contact removed,
        // added back, handed a new key or marked verified during the write stays pending for the
        // next one.
        pending.added.forEach { contact ->
            contactDao.markBackedUp(
                userUuid = contact.userUuid,
                profileEncryptionKey = contact.profileEncryptionKey,
                handedProfileKeyHash = contact.handedProfileKeyHash,
                verifiedSafetyNumber = contact.verifiedSafetyNumber,
            )
        }

        contactDao.deleteRemoved(userUuids = pending.settled(isProfileKeyRotated = isProfileKeyRotated).map { it.userUuid })

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * The name [backUp] keeps in the vault for each contact in [pending] who deleted their account,
     * see [PendingBackUp.deletedAccounts]: the one their user, hidden as they were forgotten, still
     * holds, see [forget]. None for one whose profile this device never read, who simply goes.
     */
    internal suspend fun namesKept(pending: PendingBackUp): Map<String, String?> =
        pending.deletedAccounts.associate { contact ->
            contact.userUuid to userRepository.getFullNameEvenIfHidden(uuid = contact.userUuid)
        }

    /**
     * Reads the profile of every contact this device has no name for, or was missing the current
     * key of and has a new one from, saving the name on it, see
     * [ContactDao.getDueForRead][database.data.local.dao.ContactDao.getDueForRead].
     *
     * What the read says of the key it was made with goes along: one that opens no version of
     * the profile, or one that is not the latest, is most often a key the contact replaced, the
     * new one having never reached this device, and the contact is asked for theirs, see
     * [ProfileKeyPull][contact.ProfileKeyPull]. Not always, see
     * [ServedUserProfileDto.isLatest][profile.ServedUserProfileDto.isLatest], but only they can
     * tell, and their answer settles it either way, see [receiveProfileKey]. The name read is
     * saved all the same: off an older version, it is the one they had when that key was
     * replaced, which beats none. A read that opens their latest version settles it too.
     *
     * Every contact is attempted, and the first failure is reported. Only a profile that
     * could not be asked for is one, see [ContactSyncError.Profile].
     */
    suspend fun resolveNames(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        for (contact in contactDao.getDueForRead()) {
            val read = profileRepository.readFullName(
                userUuid = contact.userUuid,
                profileEncryptionKey = contact.profileEncryptionKey,
            )

            when (read) {
                is Result.Success -> {
                    saveName(userUuid = contact.userUuid, fullName = read.data.fullName)

                    if (read.data.isLatest) {
                        pendingProfileKeyDao.delete(userUuid = contact.userUuid)
                    } else {
                        pendingProfileKeyDao.recordStale(
                            userUuid = contact.userUuid,
                            profileEncryptionKey = contact.profileEncryptionKey,
                        )
                    }
                }

                is Result.Error -> when (read.error) {
                    // Pruned since, or never stored: the key opens nothing of theirs any more.
                    // One gone with its owner's account reads the same, and is asked about until
                    // the request finds the account deleted, which forgets them, see
                    // receiveAccountDeletion.
                    ProfileError.NotStored -> pendingProfileKeyDao.recordStale(
                        userUuid = contact.userUuid,
                        profileEncryptionKey = contact.profileEncryptionKey,
                    )

                    // One contact's profile failing says nothing about the next one's.
                    is ProfileError.Network -> failure = failure ?: ContactSyncError.Profile(read.error)

                    // A version that does not open with the key it was asked for under is its
                    // owner's to put right, not a key gone missing.
                    else -> Unit
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
     * The safety number the user verified with each of them comes back too, see
     * [ContactEntity.verifiedSafetyNumber], and counts again only if it is still the one computed
     * from the identities now pinned, see [SafetyNumbers][contact.SafetyNumbers]. So does the name
     * of each contact who deleted their account, as nobody's contact, see [putBack].
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
        val book = when (val read = vaultRepository.getContactBook()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(ContactSyncError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(ContactSyncError.Identity(opened.error))
        }

        try {
            book.contacts.forEach { (userUuid, contact) ->
                val signatureKey = contact.mlsSignaturePublicKey ?: return@forEach

                if (!client.restorePin(peerUuid = userUuid, signatureKey = signatureKey)) {
                    identityConflictDao.insert(IdentityConflictEntity(peerUuid = userUuid))
                }
            }
        } catch (_: MlsException) {
            return Result.Error(ContactSyncError.Storage)
        }

        putBack(book = book)

        clearContactsToRestore()

        return Result.Success(Unit)
    }

    /**
     * Puts what [book] holds back on this device, for [bringBack] once the pins are back: each
     * contact, as the vault holds them, and the name of each one who deleted their account.
     *
     * That name comes back as a hidden user, as forgetting them left it, see [forget]: the
     * expenses the two of them share show it, the contact list does not, and no contact comes
     * back with it, there being nobody left to reach, see
     * [ContactBook.deletedAccounts][vault.ContactBook.deletedAccounts].
     */
    internal suspend fun putBack(book: ContactBook) {
        contactDao.insertNew(
            contacts = book.contacts.map { (userUuid, contact) ->
                ContactEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = contact.profileEncryptionKey,
                    handedProfileKeyHash = contact.handedProfileKeyHash,
                    verifiedSafetyNumber = contact.verifiedSafetyNumber,
                    isBackedUp = true,
                )
            },
        )

        book.deletedAccounts.forEach { (userUuid, fullName) ->
            saveName(userUuid = userUuid, fullName = fullName, isHidden = true)
        }

        // Whoever came back may wake the user again, the blocked ones aside.
        wakeList.rebuild()
    }

    /**
     * Rotates the key to the user's profile for the removals in [rotating], see
     * [ProfileRepository.rotateProfileKey], then records that they no longer wait for it.
     *
     * Only those: a removal the user makes between the rotation and this owes the key another,
     * see [ProfileRepository.oweProfileKeyRotation], the contact it removes having perhaps been
     * handed the new key in between, by a [ProfileKeyDelivery][contact.ProfileKeyDelivery]
     * running alongside. One made while this backup ran, before the rotation, had its key taken
     * by it, and the next backup finds nothing left owed for it.
     */
    private suspend fun rotateProfileKey(rotating: List<ContactEntity>): EmptyResult<ContactSyncError> {
        val userUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(ContactSyncError.Identity(MlsIdentityError.MissingSession))

        val rotation = profileRepository.rotateProfileKey(userUuid = userUuid)

        if (rotation is Result.Error) return Result.Error(ContactSyncError.ProfileKey(rotation.error))

        contactDao.clearProfileKeyRotation(userUuids = rotating.map { it.userUuid })

        return Result.Success(Unit)
    }

    /**
     * Tells the contact [userUuid], in the group [mlsGroupId], what [notice] says: that the user
     * removed them, or deleted their account.
     *
     * Posted here rather than through [message.DirectMessageSender], which brings the server's
     * word that an account is gone to [receiveAccountDeletion] and so needs this repository: what
     * the sender would do is left to the callers, a removal forgetting them and a deletion having
     * no use for it, see [RemovalNotice].
     *
     * What the server answers is read by [toRemovalNotice].
     */
    private suspend fun postNotice(
        client: MlsClient,
        userUuid: String,
        mlsGroupId: ByteArray,
        notice: ByteArray,
    ): Result<RemovalNotice, RemoveContactError> {
        val message = try {
            client.encryptApplicationMessage(
                mlsGroupId = mlsGroupId,
                payload = notice,
            )
        } catch (exception: MlsException) {
            if (exception.isRetryable()) return Result.Error(RemoveContactError.Storage)

            // The group is unusable for good, so there is no way left to tell them, and
            // refusing to remove the contact would keep them forever.
            return Result.Success(RemovalNotice.DONE)
        }

        return directMessageApi.sendDirectMessage(
            body = SendDirectMessageBody(
                recipientUserUuid = userUuid,
                ciphertext = DirectMessageEnvelope(mlsGroupId = mlsGroupId, message = message).serialize(),
            ),
        ).toRemovalNotice()
    }

    /**
     * Removes the contact [userUuid] from this device, leaving a tombstone for [backUp] to
     * carry the removal to the vault, and deletes the groups the two of them shared.
     *
     * Their user stays, hidden from the contact list, rather than being deleted: a user can
     * be more than a contact, a group the two of them are in still lists them, and an expense
     * they share still names them, by the name [backUp] keeps in the vault for one who deleted
     * their account. Their channel row stays too, emptied, see [DirectChannelEntity].
     *
     * What this device is still waiting for from them is noted first: the keys to those
     * expenses are the only ones they can still hand over, see
     * [PendingExpenseKeyEntity.isAwaitedAtRemoval][database.data.local.entity.PendingExpenseKeyEntity.isAwaitedAtRemoval].
     *
     * [rotatesProfileKey] is whether the tombstone waits for the user's profile key to rotate,
     * see [ContactEntity.rotatesProfileKey]. One that does is written once the rotation is owed,
     * under the lock every rotation of the key and edit of the profile takes, see [remove] and
     * [ProfileRepository.oweProfileKeyRotation]: from then on, nothing the user writes goes under
     * the key the contact holds. [isAccountDeleted] is whether they deleted their account, see
     * [ContactEntity.isAccountDeleted].
     *
     * Nor is their own profile key asked for any longer, see
     * [PendingProfileKeyEntity][database.data.local.entity.PendingProfileKeyEntity].
     */
    private suspend fun forget(
        userUuid: String,
        client: MlsClient?,
        rotatesProfileKey: Boolean,
        isAccountDeleted: Boolean,
    ) {
        contactDao.get(userUuid = userUuid) ?: return

        // First, so that there is no moment when they are no longer a contact and what they
        // owe is not noted yet: a key of theirs read then would be dropped. And only once they
        // are known to be one: a notice of removal from anybody else would make whatever they
        // put the user on owed.
        pendingExpenseKeyDao.markAwaitedAtRemoval(creditorUuid = userUuid)

        contactDao.markRemoved(
            userUuid = userUuid,
            rotatesProfileKey = rotatesProfileKey,
            isAccountDeleted = isAccountDeleted,
        )

        pendingProfileKeyDao.delete(userUuid = userUuid)

        // Straight away: a former contact must not wake the user a moment longer, see
        // [WakeList].
        wakeList.rebuild()

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
            // since it looked. A notice of removal passes none either, opened only for a group.
            val deleting = client
                ?: held.takeIf { it.isNotEmpty() }?.let { (mlsIdentityRepository.client() as? Result.Success)?.data }

            held.forEach { deleting?.deleteGroupQuietly(mlsGroupId = it) }
        }
    }

    /** Saves [fullName] as the name of [userUuid], [isHidden] from the contact list or not. */
    private suspend fun saveName(userUuid: String, fullName: String, isHidden: Boolean = false) {
        userRepository.insert(
            User(
                uuid = userUuid,
                // Only the user's own is known, a contact's discovery handle never reaches
                // this device.
                username = "",
                fullName = fullName,
                isDeleted = isHidden,
            ),
        )
    }
}

/**
 * What became of a notice of removal that leaves nothing to try again, see
 * `ContactRepository.postNotice`.
 *
 * [rotatesProfileKey] is whether the contact it was for, once forgotten, waits on a rotation of
 * the key to the user's profile, see [ContactEntity.rotatesProfileKey], and [isAccountDeleted]
 * whether they deleted their account, see [ContactEntity.isAccountDeleted].
 */
internal enum class RemovalNotice(val rotatesProfileKey: Boolean, val isAccountDeleted: Boolean) {

    /** Out, or with no way left to send it. They hold the key, which rotates away from them. */
    DONE(rotatesProfileKey = true, isAccountDeleted = false),

    /**
     * Not out: the contact deleted their account, see [SendDirectMessageError.RecipientDeleted],
     * and the key they held went with it, see `ContactRepository.receiveAccountDeletion`, which
     * keeps their name.
     */
    RECIPIENT_DELETED(rotatesProfileKey = false, isAccountDeleted = true),
}

/**
 * What the server's answer to a notice of removal comes to, for `ContactRepository.remove` to
 * act on, see [RemovalNotice], or to report for the user to try again.
 *
 * A refusal for either of the two limits is reported: the user is the one waiting on a removal,
 * and tries again. Which one it was is for an account deletion, which tells every contact this
 * device holds a group with, one after the other, see `ContactRepository.sendNotice`.
 *
 * A `404` is no way left to send it, as for a contact with no group left to tell them in: an
 * account an administrator removed answers one for good, which nothing else would ever drop
 * them for, so a removal refused on it could never go through. It is also what the proxy in
 * front of the API answers every route with while the API is being redeployed, and a removal
 * that goes ahead then leaves the contact holding the user, but not reading anything they write
 * after, see [ContactEntity.rotatesProfileKey]. Nobody is forgotten for an account deleted on
 * it, which only a `410` says, see [SendDirectMessageError.RecipientDeleted].
 */
internal fun EmptyResult<SendDirectMessageError>.toRemovalNotice(): Result<RemovalNotice, RemoveContactError> =
    when (this) {
        is Result.Success -> Result.Success(RemovalNotice.DONE)

        is Result.Error -> when (val refusal = error) {
            is SendDirectMessageError.RateLimited -> Result.Error(RemoveContactError.RateLimited(scope = refusal.scope))

            SendDirectMessageError.RecipientDeleted -> Result.Success(RemovalNotice.RECIPIENT_DELETED)

            is SendDirectMessageError.Network ->
                if (refusal.error == NetworkError.NOT_FOUND) {
                    Result.Success(RemovalNotice.DONE)
                } else {
                    Result.Error(RemoveContactError.Network(refusal.error))
                }
        }
    }

/**
 * The changes [ContactRepository.backUp] read, sorted by what each has to wait for, and by what
 * the vault is to make of them.
 *
 * Only the removals the user made wait, for the key to their profile to rotate, see
 * [ContactEntity.rotatesProfileKey]. The rest goes out whatever comes of that.
 */
internal class PendingBackUp(private val contacts: List<ContactEntity>) {

    /** The contacts the vault does not hold as they are on this device. */
    val added: List<ContactEntity> = contacts.filterNot { it.isRemoved }

    /** The removals that wait for the user's profile key to rotate. */
    val rotating: List<ContactEntity> = contacts.filter { it.isRemoved && it.rotatesProfileKey }

    /**
     * The contacts who deleted their account, whose entry the vault turns into one holding their
     * name rather than drops, when this device holds one for them, see
     * [ContactEntity.isAccountDeleted] and [ContactRepository.namesKept]. None waits for the key.
     */
    val deletedAccounts: List<ContactEntity> = contacts.filter { it.isRemoved && it.isAccountDeleted }

    fun isEmpty(): Boolean = contacts.isEmpty()

    /**
     * The removals to write, the vault dropping the contact: every one once the key has rotated,
     * and otherwise only those that do not wait for it. Not the [deletedAccounts].
     */
    fun removals(isProfileKeyRotated: Boolean): List<ContactEntity> =
        contacts.filter { it.isRemoved && !it.isAccountDeleted && (isProfileKeyRotated || !it.rotatesProfileKey) }

    /**
     * Whether the vault has anything to take: an addition, a removal to write, see [removals], or
     * a contact who deleted their account, who waits for nothing. Left out, one pending alone
     * would stay on the device until some other change came along, and a log-out in between would
     * lose their name for good.
     */
    fun writesAnything(isProfileKeyRotated: Boolean): Boolean =
        added.isNotEmpty() || removals(isProfileKeyRotated = isProfileKeyRotated).isNotEmpty() || deletedAccounts.isNotEmpty()

    /**
     * The tombstones a write that landed is done with, for the device to delete: the [removals] it
     * carried and the [deletedAccounts]. Kept, one would be written again by every backup after.
     */
    fun settled(isProfileKeyRotated: Boolean): List<ContactEntity> =
        removals(isProfileKeyRotated = isProfileKeyRotated) + deletedAccounts
}
