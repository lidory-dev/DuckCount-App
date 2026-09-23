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
import uniffi.lib_duckcount.ProcessedMessage
import kotlin.io.encoding.Base64

/** What reading an application message out of a group came to. */
internal sealed interface ApplicationMessageReading {

    /** The plaintext, from the member MLS authenticated as its sender. */
    class Read(val senderUserUuid: String, val payload: ByteArray) : ApplicationMessageReading

    /**
     * The message will never be read: it is malformed, it is not an application message,
     * or the group it belongs to is not on this device.
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
 */
internal suspend fun MlsClient.readApplicationMessage(
    mlsGroupId: ByteArray,
    message: ByteArray,
): ApplicationMessageReading {
    val processed = try {
        withGroup(mlsGroupId = mlsGroupId) { group ->
            group.processIncomingMessage(message = message)
        }
    } catch (exception: MlsException) {
        return if (exception.isRetryable()) {
            ApplicationMessageReading.Retry
        } else {
            ApplicationMessageReading.Unreadable
        }
    }

    return when (processed) {
        is ProcessedMessage.ApplicationMessage -> ApplicationMessageReading.Read(
            senderUserUuid = processed.senderUserUuid,
            payload = processed.payload,
        )

        else -> ApplicationMessageReading.Unreadable
    }
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
 * Joins the group [welcome] was sealed for, and returns its id.
 *
 * The other operation [withGroup] cannot cover: the id is only known once the join has
 * stored it, which is also why the id a caller was *told* the group has is worth nothing
 * next to the one this returns.
 */
internal suspend fun MlsClient.joinGroupFrom(welcome: ByteArray): ByteArray =
    withContext(Dispatchers.IO) {
        joinGroup(welcome = welcome)
    }

/**
 * The MLS identity this device pinned for [peerUuid], or null when it has never met them.
 *
 * What gets backed up, so that a log-out does not leave the next login pinning whichever
 * key package the server happens to serve.
 */
internal suspend fun MlsClient.pinnedSignatureKeyFor(peerUuid: String): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            pinnedSignatureKey(userUuid = peerUuid)
        } catch (_: MlsException) {
            null
        }
    }

/**
 * Puts back the identity pinned for [peerUuid], and says whether it agreed with what this
 * device already held.
 *
 * False means the restored pin and a pin made since name different keys, which is the event
 * pinning exists to report: something served a different identity for this contact. It is
 * reported rather than resolved, since only the user comparing safety numbers with the peer
 * can say which is right.
 */
internal suspend fun MlsClient.restorePin(peerUuid: String, signatureKey: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        try {
            pinIdentity(userUuid = peerUuid, signatureKey = signatureKey)

            true
        } catch (exception: MlsException) {
            exception !is MlsException.IdentityKeyMismatch
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
 * Only the user's own storage failing, or a lock poisoned for the rest of this process,
 * leaves the stored state as it was. Every other refusal is about the message or the group
 * itself, and trying again will not change the answer.
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
 * consumed, and one that never saw a commit drops the epoch the peers have moved to.
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
 * A lock stays behind for every group the session touched, which is a mutex per contact
 * and per group the user belongs to.
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
