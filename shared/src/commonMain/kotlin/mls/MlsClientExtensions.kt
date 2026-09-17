package mls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * whose id is either known to nobody yet or known only once the join has stored it.
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
