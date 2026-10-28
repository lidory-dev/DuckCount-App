package mls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.lib_duckcount.AddMembersOutput
import uniffi.lib_duckcount.GroupCreation
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsGroup
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.WelcomeInspection
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
 * the group. Every group a message is read in is shared with one peer and never changes,
 * and which group that is, its sender names: the roster [joinGroupFrom] checked would
 * otherwise be theirs to change. The user's groups of several members are the exception
 * this device holds, whose creator changes them, which the group sync reads in instead, see
 * [processGroupMessageIn], and which a direct message naming one is never read in: the
 * direct-message sync checks and reads under [group.withGroupJoinLock], which a group is
 * joined and saved as one of them under, see [message.DirectMessageSync].
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

/** What a message waiting in a group of several members came to, see [processGroupMessageIn]. */
internal class ProcessedGroupMessage(
    val processed: ProcessedMessage,

    /**
     * Every member the group holds once the message is processed, the user included, as their
     * credentials name them, when it was a Commit the user is still in the group after; null for
     * anything else.
     */
    val memberUuidsAfterCommit: List<String>?,
)

/**
 * Processes [message], one waiting for the user in the group of several members [mlsGroupId],
 * taking a Commit from [creatorUuid] alone and a Proposal from nobody, see
 * [MlsGroup.processIncomingMessage]: only the creator of a group the server holds changes its
 * members, see [group.GroupMembershipChanger]. Anything else changing the group is refused with
 * [MlsException.UnauthorizedChange], and stores nothing.
 *
 * Messages are processed in the order the server lists them, as a Commit read out of order is
 * refused, and leaves the group behind.
 *
 * Throws [MlsException] like every other operation on a group.
 */
internal suspend fun MlsClient.processGroupMessageIn(
    mlsGroupId: ByteArray,
    message: ByteArray,
    creatorUuid: String,
): ProcessedGroupMessage = withGroup(mlsGroupId = mlsGroupId) { group ->
    val processed = group.processIncomingMessage(message = message, allowedCommitter = creatorUuid)

    // Read from the same copy, the tree the Commit left, before anything else can reach it.
    val memberUuids = if (processed is ProcessedMessage.Commit && !processed.removedSelf) {
        group.members().map { it.userUuid }
    } else {
        null
    }

    ProcessedGroupMessage(processed = processed, memberUuidsAfterCommit = memberUuids)
}

/** Who is in a group, as far as changing its members goes, see [rosterOf]. */
internal class GroupRoster(
    /** Every member, the user included, as their credentials name them. */
    val memberUuids: List<String>,

    /** The epoch the group is in. */
    val epoch: Long,

    /** Whether a Commit of the user's is pending in it: built, and neither applied nor discarded. */
    val hasPendingCommit: Boolean,
)

/**
 * Who is in the group [mlsGroupId], by its tree rather than by anything the server said, see
 * [GroupRoster].
 */
internal suspend fun MlsClient.rosterOf(mlsGroupId: ByteArray): GroupRoster =
    withGroup(mlsGroupId = mlsGroupId) { group ->
        GroupRoster(
            memberUuids = group.members().map { it.userUuid },
            epoch = group.epoch().toLong(),
            hasPendingCommit = group.hasPendingCommit(),
        )
    }

/**
 * Builds the Commit adding the owners of [invitees] to the group [mlsGroupId], and the one Welcome
 * that lets them all in, carrying [welcomePayload] to each of them, see [MlsGroup.addMembers]. The
 * Commit stays pending until [applyPendingCommitIn] or [discardPendingCommitIn].
 */
internal suspend fun MlsClient.addMembersTo(
    mlsGroupId: ByteArray,
    invitees: List<InviteeKeyPackage>,
    welcomePayload: ByteArray,
): AddMembersOutput = withGroup(mlsGroupId = mlsGroupId) { group ->
    group.addMembers(invitees = invitees, welcomePayload = welcomePayload)
}

/**
 * Builds the Commit removing [userUuids] from the group [mlsGroupId], see
 * [MlsGroup.removeMembers]. It stays pending until [applyPendingCommitIn] or
 * [discardPendingCommitIn].
 */
internal suspend fun MlsClient.removeMembersFrom(mlsGroupId: ByteArray, userUuids: List<String>): ByteArray =
    withGroup(mlsGroupId = mlsGroupId) { group -> group.removeMembers(userUuids = userUuids) }

/**
 * Applies the Commit of the user's pending in the group [mlsGroupId], once the server took it, and
 * returns every member the group holds then, the user included.
 */
internal suspend fun MlsClient.applyPendingCommitIn(mlsGroupId: ByteArray): List<String> =
    withGroup(mlsGroupId = mlsGroupId) { group ->
        group.applyPendingCommit()

        group.members().map { it.userUuid }
    }

/**
 * Discards the Commit of the user's pending in the group [mlsGroupId]: the server refused it, or it
 * was never sent. The group stays in the epoch it is in.
 */
internal suspend fun MlsClient.discardPendingCommitIn(mlsGroupId: ByteArray) =
    withGroup(mlsGroupId = mlsGroupId) { group -> group.clearPendingCommit() }

/**
 * Deletes a group this device has no further use for.
 *
 * A failure is not reported: whatever made the group useless has already happened, and a
 * group left behind holds nothing a peer can still reach. Only one the user was removed from, or
 * left, is in the way of anything, the Welcome adding them back, and is deleted again before that
 * is read, see [deleteGroupOf].
 */
internal suspend fun MlsClient.deleteGroupQuietly(mlsGroupId: ByteArray) {
    try {
        deleteGroupOf(mlsGroupId = mlsGroupId)
    } catch (_: MlsException) {
        // See above.
    }
}

/**
 * Deletes the group [mlsGroupId], one already deleted staying as it is, see
 * [MlsClient.deleteGroup].
 *
 * Throws [MlsException] when the user's storage fails, which leaves the group held: what a group
 * the user was removed from, or left, is deleted with before they are let back in, the state
 * still held refusing every Welcome to it, see [MlsClient.inspectWelcome].
 */
internal suspend fun MlsClient.deleteGroupOf(mlsGroupId: ByteArray) {
    withGroupLock(mlsGroupId = mlsGroupId) {
        withContext(Dispatchers.IO) {
            deleteGroup(mlsGroupId = mlsGroupId)
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
 * Its id is a channel's, derived from a nonce the library draws, which no group the server
 * holds can have, see [joinGroupFrom].
 *
 * One of the two operations [withGroup] cannot cover, its id being known to nobody until
 * it returns, so it carries its own dispatcher. Callers that build a group for a peer take
 * [withPeerLock] around the whole check-then-act instead, which is what keeps two of these
 * from running for the same peer.
 */
internal suspend fun MlsClient.createGroupWith(invitee: InviteeKeyPackage): GroupCreation =
    withContext(Dispatchers.IO) {
        createGroup(groupUuid = null, invitees = listOf(invitee), welcomePayload = null)
    }

/**
 * Builds the group the server is to hold under [groupUuid], holding the user and every one of
 * [invitees], and returns it with the one Welcome that lets them all in, carrying [welcomePayload]
 * to each of them.
 *
 * What a group the user creates is built with, as [createGroupWith] builds a channel. Its id is
 * derived from [groupUuid], which is what its invitees join it as, see [inspectGroupWelcome]. There
 * is no group to lock until this returns, as there, so it carries its own dispatcher, and what
 * stands in for the group lock around it is [group.withGroupsLock], held by the creation for the
 * whole of it.
 */
internal suspend fun MlsClient.createGroupOf(
    groupUuid: String,
    invitees: List<InviteeKeyPackage>,
    welcomePayload: ByteArray,
): GroupCreation =
    withContext(Dispatchers.IO) {
        createGroup(groupUuid = groupUuid, invitees = invitees, welcomePayload = welcomePayload)
    }

/**
 * Reads the Welcome to the group of several members the server lists as [groupUuid], [welcome],
 * for who wrote it, who else is in the group and what it hands over, and stores nothing, see
 * [MlsClient.inspectWelcome].
 *
 * What a group the user was added to is trusted or refused on before [joinGroupOf] stores it: a
 * group once stored is never joined again, deleted or not, so a Welcome joined only to be refused,
 * or left waiting, would leave its group unjoinable here. A Welcome to any group but [groupUuid]
 * is refused as well, the library deriving the group's id from its UUID, which no channel or
 * contact request can have. Any member can derive it too, and create a group of their own under
 * [groupUuid], whose Welcome this would read as one to that group: [welcome] is to be the one the
 * server wrote with the group, see [group.GroupSync]. The members' identities are checked against
 * those pinned for them, so a writer this device pinned is the one who signed it.
 *
 * Throws [MlsException] as the join does for a Welcome that is no way in, or when storage fails.
 */
internal suspend fun MlsClient.inspectGroupWelcome(welcome: ByteArray, groupUuid: String): WelcomeInspection =
    withContext(Dispatchers.IO) {
        inspectWelcome(welcome = welcome, expectedGroupUuid = groupUuid)
    }

/**
 * Joins the group of several members the server lists as [groupUuid], from the Welcome [welcome],
 * and returns its id, pinning nobody.
 *
 * What a group the user was added to is joined with, as [joinGroupFrom] joins a channel, once
 * [inspectGroupWelcome] found it worth joining. The join is refused unless the Welcome is to
 * [groupUuid], as the inspection checked, and the group holds exactly the user and
 * [expectedMembers], the members that inspection read in the Welcome's tree, which whoever wrote
 * it signed. Not those the server lists: that list is today's, the tree the one of the epoch the
 * Welcome opens, and a member who left the group since, or deleted their account, is gone from
 * the one and still in the other until the creator's device next syncs and removes them, see
 * [group.GroupSync].
 *
 * Those this device pinned an identity for are checked against it, the others left unpinned:
 * theirs is whatever key package the server served whoever wrote the Welcome, which is not
 * something to take its word for, and nothing here is read from them.
 *
 * Carries its own dispatcher, there being no group to lock until the join has stored it, as for
 * [joinGroupFrom], and what stands in for the group lock around it is [group.withGroupsLock],
 * held by the group sync for the whole of it.
 */
internal suspend fun MlsClient.joinGroupOf(
    welcome: ByteArray,
    groupUuid: String,
    expectedMembers: List<String>,
): ByteArray =
    withContext(Dispatchers.IO) {
        joinGroup(
            welcome = welcome,
            expectedGroupUuid = groupUuid,
            expectedMembers = expectedMembers,
            pinMembers = false,
        )
    }

/**
 * Joins the group [welcome] was sealed for, and returns its id, pinning [peerUuid] when
 * [pinPeer] says this device already trusts them, and leaving that to [pinPeerIn] otherwise.
 *
 * [peerUuid] is who the server says sent the Welcome, which it knows from their session.
 * The join is refused unless the group holds exactly the user and [peerUuid], so a
 * Welcome from one user cannot make a channel to another, or seat anybody else in it, and
 * unless its id is a channel's, see [createGroupWith]: a Welcome given the id of a group the
 * server holds, which every member of that group reads, would otherwise be stored under it,
 * and leave that group refused as already joined, see [inspectGroupWelcome].
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
        joinGroup(
            welcome = welcome,
            expectedGroupUuid = null,
            expectedMembers = listOf(peerUuid),
            pinMembers = pinPeer,
        )
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
 * Pins [userUuid] to the identity they hold in the group of several members [mlsGroupId], and
 * nobody else, see [MlsGroup.pinMember]: a group the server holds, whose tree is its creator's
 * word, which this device takes for one of its members before sending them a contact request, so
 * that the key package the server serves for them is checked against it, see
 * [contact.ContactRequestSender].
 *
 * Throws [MlsException.UnexpectedMembers] when no member of the group but the user holds
 * [userUuid], and [MlsException.IdentityKeyMismatch] when a different identity is pinned for them,
 * which stays pinned.
 */
internal suspend fun MlsClient.pinMemberOf(mlsGroupId: ByteArray, userUuid: String) =
    withGroup(mlsGroupId = mlsGroupId) { it.pinMember(userUuid = userUuid) }

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
 * The safety number the user and [peerUuid] compare to tell that nobody stands between them, or
 * null when this device pinned no identity for [peerUuid].
 *
 * Made of the user's own identity and the one pinned for [peerUuid], never of a key package or
 * anything else the server serves: the two phones show the same number only when each pinned
 * the other's real identity, see [MlsClient.safetyNumber].
 *
 * Throws [MlsException] when the pin could not be read.
 */
internal suspend fun MlsClient.safetyNumberWith(peerUuid: String): String? =
    withContext(Dispatchers.IO) { safetyNumber(peerUserUuid = peerUuid) }

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
 * them is [withPeerLock], which locks the peer instead, and [createGroupOf] and
 * [joinGroupOf], around which [group.withGroupsLock] does.
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
