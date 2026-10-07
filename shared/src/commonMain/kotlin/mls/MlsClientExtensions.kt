package mls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.lib_duckcount.GroupCreation
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsGroup
import kotlin.io.encoding.Base64

/** What reading an application message out of a group came to. */
internal sealed interface ApplicationMessageReading {

    /**
     * The plaintext, from the member MLS authenticated as its sender, and whether they
     * signed it with the key pinned for them: false for anybody this device has not chosen
     * to trust.
     */
    class Read(
        val senderUserUuid: String,
        val payload: ByteArray,
        val isSenderPinned: Boolean,
    ) : ApplicationMessageReading

    /**
     * The message will never be read: it is malformed, it is not an application message,
     * the group it belongs to is not on this device, or reading it would grow that group
     * past the most a group may store, its sender having skipped too many messages.
     */
    data object Unreadable : ApplicationMessageReading

    /**
     * The user's own storage failed before anything was stored, so the same message can
     * be read again later.
     */
    data object Retry : ApplicationMessageReading
}

/**
 * Reads the application message a peer encrypted to the group [mlsGroupId].
 *
 * Reading consumes the message: MLS deletes the key that decrypts it as soon as the new
 * state is stored, so a message that was read has to be acted on, or kept, by the caller.
 *
 * Anything else, a Commit or a Proposal, is unreadable, and refused before it can change
 * the group. Every group this device holds is shared with one peer and never changes, and
 * the group a message is read in is whichever its sender named: the roster [joinGroupFrom]
 * checked would otherwise be theirs to change.
 */
internal suspend fun MlsClient.readApplicationMessage(
    mlsGroupId: ByteArray,
    message: ByteArray,
): ApplicationMessageReading {
    val read = try {
        withGroup(mlsGroupId = mlsGroupId) { group ->
            group.readApplicationMessage(message = message)
        }
    } catch (exception: MlsException) {
        return if (exception.isRetryable()) {
            ApplicationMessageReading.Retry
        } else {
            ApplicationMessageReading.Unreadable
        }
    }

    return ApplicationMessageReading.Read(
        senderUserUuid = read.senderUserUuid,
        payload = read.payload,
        isSenderPinned = read.isSenderPinned,
    )
}

/**
 * Encrypts [payload] for the members of the group [mlsGroupId].
 *
 * It throws [MlsException] like every other operation on a group, and [isRetryable] tells
 * a storage failure from a group that is unusable for good.
 */
internal suspend fun MlsClient.encryptApplicationMessage(
    mlsGroupId: ByteArray,
    payload: ByteArray,
): ByteArray = withGroup(mlsGroupId = mlsGroupId) { group ->
    group.encryptApplicationMessage(payload = payload)
}

/**
 * Deletes a group this device has no further use for.
 *
 * A failure is not reported: whatever made the group useless has already happened, and a
 * group left behind holds nothing a peer can still reach.
 */
internal suspend fun MlsClient.deleteGroupQuietly(mlsGroupId: ByteArray) {
    withGroupLock(mlsGroupId = mlsGroupId) {
        withContext(Dispatchers.IO) {
            try {
                deleteGroup(mlsGroupId = mlsGroupId)
            } catch (_: MlsException) {
                // See above.
            }
        }
    }
}

/**
 * Deletes the secrets of the user's key packages that expired long enough ago that no
 * Welcome can still need them, see [MlsClient.pruneKeyPackages].
 *
 * A failure is not reported: whatever is not deleted now, the next publication deletes.
 */
internal suspend fun MlsClient.pruneKeyPackagesQuietly() {
    withContext(Dispatchers.IO) {
        try {
            pruneKeyPackages()
        } catch (_: MlsException) {
            // See above.
        }
    }
}

/**
 * Builds a group holding the user and [invitee], and returns it with the Welcome that lets
 * them in.
 *
 * One of the two operations [withGroup] cannot cover, its id being known to nobody until
 * it returns, so it carries its own dispatcher. Callers that build a group for a peer take
 * [withPeerLock] around the whole check-then-act instead, which is what keeps two of these
 * from running for the same peer.
 */
internal suspend fun MlsClient.createGroupWith(invitee: InviteeKeyPackage): GroupCreation =
    withContext(Dispatchers.IO) {
        createGroup(invitees = listOf(invitee))
    }

/**
 * Joins the group [welcome] was sealed for, and returns its id, pinning [peerUuid] when
 * [pinPeer] says this device already trusts them, and leaving that to [pinPeerIn] otherwise.
 *
 * [peerUuid] is who the server says sent the Welcome, which it knows from their session.
 * The join is refused unless the group holds exactly the user and [peerUuid], so a
 * Welcome from one user cannot make a channel to another, or seat anybody else in it.
 *
 * The other operation [withGroup] cannot cover: the id is only known once the join has
 * stored it, which is also why the id a caller was *told* the group has is worth nothing
 * next to the one this returns.
 */
internal suspend fun MlsClient.joinGroupFrom(
    welcome: ByteArray,
    peerUuid: String,
    pinPeer: Boolean,
): ByteArray =
    withContext(Dispatchers.IO) {
        joinGroup(welcome = welcome, expectedMembers = listOf(peerUuid), pinMembers = pinPeer)
    }

/**
 * Whether the group [mlsGroupId] is still stored on this device.
 *
 * Only a group found missing says no: one that could not be read for any other reason is
 * taken to be there, as it most likely is.
 */
internal suspend fun MlsClient.holdsGroup(mlsGroupId: ByteArray): Boolean =
    try {
        withGroup(mlsGroupId = mlsGroupId) {}

        true
    } catch (exception: MlsException) {
        exception !is MlsException.GroupNotFound
    }

/**
 * Pins [peerUuid] to the identity they hold in the group [mlsGroupId], which was joined
 * without pinning them.
 *
 * Throws [MlsException.IdentityKeyMismatch] when a different identity was pinned for
 * [peerUuid] since.
 */
internal suspend fun MlsClient.pinPeerIn(mlsGroupId: ByteArray, peerUuid: String) =
    withGroup(mlsGroupId = mlsGroupId) { it.pinMembers(expectedMembers = listOf(peerUuid)) }

/**
 * Checks the identity [peerUuid] holds in the group [mlsGroupId] against the one pinned for
 * them, if any, and pins nothing: what [pinPeerIn] checks, before trusting them with
 * something that may never land.
 *
 * Throws [MlsException.IdentityKeyMismatch] when a different identity is pinned for
 * [peerUuid].
 */
internal suspend fun MlsClient.verifyPeerIn(mlsGroupId: ByteArray, peerUuid: String) =
    withGroup(mlsGroupId = mlsGroupId) { it.verifyMembers(expectedMembers = listOf(peerUuid)) }

/**
 * The MLS identity this device pinned for [peerUuid], or null when it has never met them.
 *
 * What gets backed up, so that a log-out does not leave the next login pinning whichever
 * key package the server happens to serve.
 *
 * Throws [MlsException] when the pin could not be read, which every caller has to tell
 * apart from there being none: taken for none, it would be backed up as missing for good,
 * or a key checked against nothing at all.
 */
internal suspend fun MlsClient.pinnedSignatureKeyFor(peerUuid: String): ByteArray? =
    withContext(Dispatchers.IO) { pinnedSignatureKey(userUuid = peerUuid) }

/**
 * Puts back the identity pinned for [peerUuid], and says whether it agreed with what this
 * device already held.
 *
 * False means the restored pin and a pin made since name different keys, which is the event
 * pinning exists to report: something served a different identity for this contact. It is
 * reported rather than resolved, since only the user comparing safety numbers with the peer
 * can say which is right.
 *
 * Throws [MlsException] when the pin could not be stored, which leaves it missing.
 */
internal suspend fun MlsClient.restorePin(peerUuid: String, signatureKey: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        try {
            pinIdentity(userUuid = peerUuid, signatureKey = signatureKey)

            true
        } catch (_: MlsException.IdentityKeyMismatch) {
            false
        }
    }

/**
 * Runs [block] as the only thing reaching the channel to [peerUuid].
 *
 * [withGroup] locks a group that exists; this locks the decision of whether one should.
 * Reading the stored channel, finding none, fetching a key package and building a group is
 * a read-modify-write across two network calls, and its callers run concurrently by
 * construction: a push sync and a screen's sync, or the fan-out of a new expense and the
 * recovery of an old one, can all want the same peer at once. Two of them would each build
 * a group and each hand the peer a Welcome, the peer would join whichever reached them
 * first, and everything sent in the other would be unreadable, acknowledged, and gone.
 *
 * So the lock is held across the whole of it, and the stored channel is read again inside
 * it: the window this closes is the key-package round trip, not the group creation.
 */
internal suspend fun <T> withPeerLock(peerUuid: String, block: suspend () -> T): T {
    val lock = peerLockRegistry.withLock {
        peerLocks.getOrPut(peerUuid) { Mutex() }
    }

    return lock.withLock { block() }
}

/**
 * Whether the operation that threw may succeed if tried again later.
 *
 * Only the user's own storage failing, or a lock poisoned by a panic, which a restart
 * clears, is worth trying again later. Every other refusal is about the message or the
 * group itself, a group grown too large included, and is taken as final.
 */
internal fun MlsException.isRetryable(): Boolean =
    this is MlsException.StorageFailed || this is MlsException.LockPoisoned

/**
 * Runs [block] on the group [mlsGroupId], alone.
 *
 * Every operation on a group loads the state the store holds, advances it and writes it
 * back, and the library serialises that only within the copy one caller loaded, not over
 * the group. Two callers at once would each undo what the other stored, which for MLS is
 * no mere lost update: a copy that never saw a message puts back the key that message
 * consumed.
 *
 * Loading a group is private to this file for that reason, and every operation on one goes
 * through a function above. The two this cannot cover are creating and joining a group,
 * whose id is either known to nobody yet or known only once the join has stored it: those
 * are [createGroupWith] and [joinGroupFrom], and what stands in for the group lock around
 * them is [withPeerLock], which locks the peer instead.
 */
private suspend fun <T> MlsClient.withGroup(mlsGroupId: ByteArray, block: (MlsGroup) -> T): T =
    withGroupLock(mlsGroupId = mlsGroupId) {
        withContext(Dispatchers.IO) {
            loadGroup(mlsGroupId = mlsGroupId).use(block)
        }
    }

/** Runs [block] holding the lock of the group [mlsGroupId], see [withGroup]. */
private suspend fun <T> withGroupLock(mlsGroupId: ByteArray, block: suspend () -> T): T {
    val lock = groupLockRegistry.withLock {
        groupLocks.getOrPut(Base64.Default.encode(mlsGroupId)) { Mutex() }
    }

    return lock.withLock { block() }
}

/**
 * One lock per group, as process-wide as the store they stand for: one client is opened
 * per process over one store, see [MlsIdentityRepository], and a group id names a group in
 * it for as long as it exists.
 *
 * A lock stays behind for every group the session touched: a mutex per group the user
 * belongs to, and one per group id a direct message named, held or not, each keyed by 32
 * bytes, see [message.DirectMessageEnvelope.parse], and at most one per message.
 */
private val groupLocks = mutableMapOf<String, Mutex>()

/** Guards [groupLocks], so two callers reaching the same group first share its lock. */
private val groupLockRegistry = Mutex()

/**
 * One lock per peer, guarding the channel to them rather than a group, see [withPeerLock].
 *
 * Keyed by user UUID rather than by group id because the whole point is the moment there is
 * no group yet.
 */
private val peerLocks = mutableMapOf<String, Mutex>()

/** Guards [peerLocks], so two callers reaching the same peer first share its lock. */
private val peerLockRegistry = Mutex()
