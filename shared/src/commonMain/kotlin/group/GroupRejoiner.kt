package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.PendingMembershipChangeEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupOf
import mls.epochKeyIn
import mls.isRetryable
import mls.rejoinGroupInfoIn
import mls.rejoinGroupOf
import uniffi.lib_duckcount.GroupRejoin
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultError
import kotlin.io.encoding.Base64

/**
 * What one pass of [GroupRejoiner.rejoinUnderGroupsLock] came to: the groups whose rejoin it sent
 * and the server has not been heard to take, [pending], which the group sync leaves alone for the
 * rest of its pass as it does every group a change of the user's waits on the server in, see
 * [GroupSync]; those it found no GroupInfo to rejoin from, or none to trust, [unanswered], whose
 * request the group sync makes again once it has taken in what it could, see [GroupApi.requestRejoin];
 * and the first thing that kept it from rejoining one, [failure], if anything.
 */
internal class GroupRejoinPass(val pending: Set<String>, val unanswered: Set<String>, val failure: GroupSyncError?)

/**
 * Brings the MLS state of a group a log-out took it from back to this device, and helps the other
 * members' devices do the same: a member rejoins their own group from outside it, the creator as
 * anybody else, with a GroupInfo another member's device exported for them.
 *
 * A log-out takes every group's MLS state with it, and the group sync brings each group back from
 * the user's copy of its key the server kept, see [GroupKeyCopies], with no MLS id, see
 * [GroupEntity.mlsGroupId]: the user is still a member, the others keep encrypting to the leaf they
 * had, and this device can read nothing they send, follow no Commit, nor make one. RFC 9420's resync
 * brings it back in step: a Commit from outside the group that removes the leaf the user had and
 * seats a new one naming them under the same signature key, which the vault brought back with the
 * user's MLS identity, see [MlsIdentityRepository]. The group holds the same members once it is
 * applied, and every member's device takes it from any member, see [mls.processGroupMessageIn].
 *
 * In this order, each pass of the group sync, see [GroupSync]:
 * 1. A group brought back asks for a GroupInfo as it is brought back, see [GroupApi.requestRejoin],
 *    which the server wakes the other current members for, see [push.PushSignal.Kind.GROUP_CHANGED],
 *    and lists the group to each of them with [GroupDto.groupInfoWanted].
 * 2. Every other member's device in the epoch the server is at publishes the GroupInfo of that
 *    epoch then, see [publishUnderGroupsLock], which the server wakes the user's device for.
 * 3. The user's device takes the first of those it can trust, see [rejoinUnderGroupsLock]: one its
 *    signer is a member this device pinned, one of the user's contacts, whose pins the vault brought
 *    back, or the user, from a device of theirs before the log-out; of the group, of the epoch the
 *    server lists, and whose tree holds the members the server lists and those who departed, and the
 *    user under the signature key this device holds, see [mls.rejoinGroupOf]. With none, or none to
 *    trust, the group sync asks again, which keeps the request from lapsing, and waits, see
 *    [GroupRejoinPass.unanswered]: once it has taken in what it could, so that a group its creator
 *    let the user in again meanwhile, removing them and adding them back, asks nobody for anything,
 *    see [GroupSync]. The request is the server's to close, which it does as it takes the rejoin, or
 *    removes the user.
 * 4. The library builds the Commit and stores the state it leads to at once, mls-rs building a
 *    Commit from outside a group already applied. Its key, that of the epoch after the GroupInfo's,
 *    is sealed as the user's copy of it, and the link back to the key of the GroupInfo's epoch made
 *    when the user's copy held that key, see [RejoinGroupBody.previousKey]. The group's MLS id and
 *    the rejoin that sends it are saved together, see [GroupDao.saveRejoin], before anything is sent.
 * 5. It is sent, see [GroupMembershipSender.send], which acts on what the server answers as for any
 *    Commit of the user's: taken, the group is in step with its members again; refused for its
 *    epoch, another Commit having got there first, its state is deleted and the group brought back
 *    as it was, for the next pass to rejoin from a GroupInfo of the epoch the server is at then; not
 *    answered, sent again by every sync until it is, see [GroupMembershipSender.resendPending].
 *
 * The GroupInfo travels through the server unsealed, there being no key the restored device holds
 * of that epoch to seal it under: the server sees the group's tree in it, its members' user UUIDs and
 * public keys, and nothing it could read the group with. Its word for who signed it is never taken,
 * and a GroupInfo signed by anybody this device does not trust is passed over for the next.
 *
 * Not a group brought back with nobody else in it, whom nobody can publish a GroupInfo for, see
 * [hasOtherMemberThan], nor one the sync never brought back, whose information did not open, see
 * [GroupSync], nor one any change is pending in. Nothing is asked for such a group either: it would
 * cost two requests a pass for as long as the group lasts, and keep open on the server a request
 * nobody could answer. That passes over the one GroupInfo it can still have, the user's own, which a
 * device of theirs exported before the log-out for a member who left since rather than rejoin, and
 * which the server keeps until the group's next epoch: its creator, the one member left in it, cannot
 * add anybody to it again, as for any group not rejoined.
 *
 * To be called under [withGroupsLock], which it does not take itself, as for a change to a group's
 * members: the group sync's.
 *
 * [client] is [MlsIdentityRepository.client], [keyCopySealer] is [GroupKeyCopies.sealer],
 * [storedGroups], [group], [hasPendingChange], [heldKeys] and [saveRejoin] are [GroupDao.getAll],
 * [GroupDao.get], [GroupDao.hasPendingChange], [GroupDao.getKeys] and [GroupDao.saveRejoin],
 * [listGroupInfos], [groupInfoOf] and [publishGroupInfo] are those of [GroupApi], [bookResend] and
 * [send] are [GroupMembershipSender.bookResend] and [GroupMembershipSender.send], or what a test
 * stands in for any of them.
 */
class GroupRejoiner internal constructor(
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val keyCopySealer: suspend () -> Result<KeyCopySealer, VaultError>,
    private val storedGroups: suspend () -> List<GroupEntity>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val heldKeys: suspend (groupUuid: String) -> List<GroupKeyEntity>,
    private val listGroupInfos: suspend (groupUuid: String) -> Result<List<GroupInfoPublisherDto>, NetworkError>,
    private val groupInfoOf: suspend (groupUuid: String, publisherUuid: String) -> Result<GroupInfoDto, NetworkError>,
    private val publishGroupInfo: suspend (groupUuid: String, groupInfo: ByteArray) -> EmptyResult<NetworkError>,
    private val saveRejoin: suspend (mlsGroupId: ByteArray, change: PendingMembershipChangeEntity) -> Unit,
    private val bookResend: suspend () -> Unit,
    private val send: suspend (
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ) -> GroupMembershipAnswer,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupKeyCopies: GroupKeyCopies,
        groupMembershipSender: GroupMembershipSender,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        client = mlsIdentityRepository::client,
        keyCopySealer = groupKeyCopies::sealer,
        storedGroups = groupDao::getAll,
        group = groupDao::get,
        hasPendingChange = groupDao::hasPendingChange,
        heldKeys = groupDao::getKeys,
        listGroupInfos = groupApi::listGroupInfos,
        groupInfoOf = groupApi::groupInfoOf,
        publishGroupInfo = groupApi::publishGroupInfo,
        saveRejoin = groupDao::saveRejoin,
        bookResend = groupMembershipSender::bookResend,
        send = groupMembershipSender::send,
    )

    /**
     * The MLS groups a rejoin stored, and could not delete again once making its keys, or saving
     * it, failed, see [attempt]: the database names none of them, so nothing else would ever delete
     * them, and the state of one held would refuse every Welcome to its group, the creator's adding
     * the user back once they removed them included, see [GroupSync]. Deleted again by every pass
     * until they are, before anything else, see [deleteUndeleted], for as long as the process lives.
     * Only touched under [withGroupsLock].
     */
    private val undeleted = mutableListOf<ByteArray>()

    /**
     * Rejoins every group brought back after a log-out that the server lists in [listings], by UUID,
     * with somebody else in it, the user being [selfUuid], see [GroupRejoiner], and says which
     * rejoins wait on the server still, which groups had nothing to rejoin from yet, and what failed
     * first, see [GroupRejoinPass]: a group that fails is left for the next sync, and the next group
     * is seen to all the same.
     *
     * A `404` from the routes rejoins go through, for a group the server lists, is a server that
     * takes none yet, which answers so until every phone it serves can read a rejoin, or the proxy in
     * front of it answering as it is redeployed: nothing to do but wait, and nothing failed, nor
     * anything to ask for. So is no GroupInfo to trust yet, the request made again by the group sync.
     * And so is the rejoin itself answered so as it is sent again, see [GroupMembershipSender].
     *
     * The key ring is read before anything is built, once in the pass, as the first group with a
     * GroupInfo to build from needs it, see [GroupKeyCopies.sealer]: as is, so that the device locking
     * throws, and the pass stops there, see [GroupSyncError.DeviceLocked]. From the build to the
     * answer is not cancellable: a state stored and never saved would be one nothing names, and one
     * saved and never sent waits for the next sync. A process that does not live from the build to
     * the save leaves one all the same, as a join does, see [GroupSync]: the next rejoin of the group
     * deletes it first, see [mls.rejoinGroupOf], the server still listing it, and nothing else can, the
     * database naming no MLS group for it, nor the library telling which id is the group's.
     */
    internal suspend fun rejoinUnderGroupsLock(listings: Map<String, GroupDto>, selfUuid: String): GroupRejoinPass {
        val stored = storedGroups()

        val restored = stored.filter { group ->
            val listing = listings[group.uuid]

            !group.isLeft &&
                group.mlsGroupId == null &&
                listing != null &&
                listing.hasOtherMemberThan(selfUuid) &&
                !hasPendingChange(group.uuid)
        }

        // Most syncs find every group held, and opening the client reads the vault.
        if (restored.isEmpty() && undeleted.isEmpty()) {
            return GroupRejoinPass(pending = emptySet(), unanswered = emptySet(), failure = null)
        }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupRejoinPass(
                pending = emptySet(),
                unanswered = emptySet(),
                failure = GroupSyncError.Identity(opened.error),
            )
        }

        // Before any group is rejoined, which builds anew in the same id.
        var failure: GroupSyncError? = deleteUndeleted(client = client, stored = stored)

        val pending = mutableSetOf<String>()
        val unanswered = mutableSetOf<String>()

        // Read once in the pass, by the first group with a GroupInfo to build from.
        var read: KeyCopySealer? = null

        val sealer: suspend () -> Result<KeyCopySealer, VaultError> = {
            read?.let { Result.Success(it) } ?: keyCopySealer().also { if (it is Result.Success) read = it.data }
        }

        for (group in restored) {
            // Between groups, never inside one: a state stored and never saved would be lost.
            currentCoroutineContext().ensureActive()

            val rejoining = rejoin(
                client = client,
                group = group,
                listing = listings.getValue(group.uuid),
                selfUuid = selfUuid,
                sealer = sealer,
            )

            when (rejoining) {
                is Rejoining.Sent -> {
                    if (hasPendingChange(group.uuid)) pending += group.uuid

                    failure = failure ?: rejoining.failure
                }

                Rejoining.Waiting -> Unit

                is Rejoining.Unanswered -> {
                    unanswered += group.uuid

                    failure = failure ?: rejoining.failure
                }

                is Rejoining.Failed -> failure = failure ?: rejoining.failure

                // The key ring failing for one group fails for the next just the same.
                is Rejoining.Stopped -> {
                    failure = failure ?: rejoining.failure

                    break
                }
            }
        }

        return GroupRejoinPass(pending = pending, unanswered = unanswered, failure = failure)
    }

    /**
     * Deletes again every MLS group of [undeleted] that no group this device holds, [stored], names,
     * and returns what kept one from being deleted, which is left for the next pass. One a group
     * names now was rejoined, or joined, since, the library deleting it first and building anew in
     * its id: that group's own state, which is not this pass's to delete.
     */
    private suspend fun deleteUndeleted(client: MlsClient, stored: List<GroupEntity>): GroupSyncError? {
        if (undeleted.isEmpty()) return null

        val stillHeld = undeleted.filterNot { mlsGroupId ->
            stored.any { it.mlsGroupId?.contentEquals(mlsGroupId) == true } || client.deletes(mlsGroupId = mlsGroupId)
        }

        undeleted.clear()
        undeleted += stillHeld

        return if (stillHeld.isEmpty()) null else GroupSyncError.Storage
    }

    /**
     * Rejoins [group], brought back after a log-out, from the first GroupInfo the server lists of the
     * epoch [listing] says it is in that this device can trust, see [GroupRejoiner], the user being
     * [selfUuid], its copy of the next key sealed with what [sealer] reads, once a GroupInfo is in
     * hand. With none, it is left for the group sync to ask for one again, see
     * [GroupRejoinPass.unanswered].
     *
     * A GroupInfo refused for anything but its signer stops the group there for the pass: the tree
     * the server serves is not the one it lists, its listing read as the pass began and a Commit
     * stored since say, and every other GroupInfo is of the same epoch.
     */
    private suspend fun rejoin(
        client: MlsClient,
        group: GroupEntity,
        listing: GroupDto,
        selfUuid: String,
        sealer: suspend () -> Result<KeyCopySealer, VaultError>,
    ): Rejoining {
        val publishers = when (val listed = listGroupInfos(group.uuid)) {
            is Result.Success -> listed.data.filter { it.epoch == listing.keyGeneration }

            // Not taken yet, see rejoinUnderGroupsLock: the request is the server's to take too.
            Result.Error(NetworkError.NOT_FOUND) -> return Rejoining.Waiting

            is Result.Error -> return Rejoining.Failed(failure = GroupSyncError.Network(listed.error))
        }

        // Whom the tree holds besides the user: the members listed, and those departed whom no
        // Commit removed yet, who are still in it.
        val expectedMembers = (listing.members.map { it.userUuid } + listing.departed.map { it.userUuid })
            .distinct() - selfUuid

        // The user's copy is of the GroupInfo's epoch: what the link back from the next key opens to.
        val previousKey = heldKeys(group.uuid)
            .firstOrNull { it.generation == listing.keyGeneration && !it.isRecovered }
            ?.groupKey

        for (publisher in publishers) {
            val published = when (val fetched = groupInfoOf(group.uuid, publisher.publisherUuid)) {
                is Result.Success -> fetched.data

                // Gone since it was listed, the group past its epoch, or the server's as above.
                Result.Error(NetworkError.NOT_FOUND) -> continue

                is Result.Error -> return Rejoining.Failed(failure = GroupSyncError.Network(fetched.error))
            }

            // The server stores a GroupInfo without reading more of it than its epoch.
            val groupInfo = runCatching { Base64.Default.decode(published.groupInfo) }.getOrNull() ?: continue

            val keySealer = when (val read = sealer()) {
                is Result.Success -> read.data
                is Result.Error -> return Rejoining.Stopped(failure = GroupSyncError.Vault(read.error))
            }

            val attempt = withContext(NonCancellable) {
                attempt(
                    client = client,
                    group = group,
                    groupInfo = groupInfo,
                    epoch = listing.keyGeneration,
                    expectedMembers = expectedMembers,
                    previousKey = previousKey,
                    sealer = keySealer,
                )
            }

            when (attempt) {
                // Another member's may be one to trust.
                Attempt.Untrusted -> continue

                is Attempt.Refused -> return Rejoining.Unanswered(failure = attempt.failure)

                is Attempt.Failed -> return Rejoining.Failed(failure = attempt.failure)

                is Attempt.Sent -> return Rejoining.Sent(failure = attempt.failure)
            }
        }

        return Rejoining.Unanswered(failure = null)
    }

    /**
     * Rejoins [group] from [groupInfo], of [epoch], whose tree is to hold the user and
     * [expectedMembers], see [rejoinGroupOf], the user's copy of the key of [epoch] being
     * [previousKey], if they hold it, and saves and sends the rejoin, its copy of the next key sealed
     * with [sealer], see [GroupRejoiner].
     *
     * Built and saved under [withGroupJoinLock], so that no direct message is read in the group
     * while the library holds its state and the database does not name it, see
     * [message.DirectMessageSync], and sent once the database does. A state that could not be saved,
     * or whose keys could not be made, is deleted again: nothing would ever send it, and nothing
     * names it. Should the deletion fail too, the user's storage failing for both, it is kept for the
     * next pass to delete, see [undeleted]. Not cancellable, which the caller sees to.
     */
    private suspend fun attempt(
        client: MlsClient,
        group: GroupEntity,
        groupInfo: ByteArray,
        epoch: Long,
        expectedMembers: List<String>,
        previousKey: ByteArray?,
        sealer: KeyCopySealer,
    ): Attempt {
        val saved = withGroupJoinLock<Attempt> {
            val rejoin = try {
                client.rejoinGroupOf(
                    groupInfo = groupInfo,
                    groupUuid = group.uuid,
                    expectedEpoch = epoch,
                    expectedMembers = expectedMembers,
                )
            } catch (exception: MlsException) {
                return@withGroupJoinLock refusalOf(exception = exception)
            }

            val change = when (
                val made = rejoinOf(
                    client = client,
                    group = group,
                    rejoin = rejoin,
                    previousKey = previousKey,
                    sealer = sealer,
                )
            ) {
                is Result.Success -> made.data

                is Result.Error -> {
                    deleteAgain(client = client, mlsGroupId = rejoin.mlsGroupId)

                    return@withGroupJoinLock Attempt.Failed(failure = made.error)
                }
            }

            try {
                saveRejoin(rejoin.mlsGroupId, change.toEntity())
            } catch (_: Exception) {
                deleteAgain(client = client, mlsGroupId = rejoin.mlsGroupId)

                return@withGroupJoinLock Attempt.Failed(failure = GroupSyncError.Storage)
            }

            Attempt.Sent(change = change, failure = null)
        }

        val change = (saved as? Attempt.Sent)?.change ?: return saved

        // Before the send, as for any change: a process not living through it leaves the rejoin to
        // go out again.
        bookResend()

        val held = checkNotNull(group(group.uuid)) { "A group rejoined is one this device holds." }

        return when (val answer = send(change, held, client)) {
            // Its state deleted again, and the request still open: the next pass rejoins anew.
            is GroupMembershipAnswer.Refused ->
                Attempt.Sent(change = change, failure = GroupSyncError.Network(answer.error))

            else -> saved
        }
    }

    /**
     * The rejoin of [group] the library built, [rejoin], with the user's copy of the key of the epoch
     * it leads to, sealed with [sealer], and the link back from it to [previousKey], the user's copy
     * of the key of the epoch it was built in, if they hold it, see [RejoinGroupBody]; or what kept
     * the key from being derived or sealed, which says nothing of the GroupInfo.
     */
    private suspend fun rejoinOf(
        client: MlsClient,
        group: GroupEntity,
        rejoin: GroupRejoin,
        previousKey: ByteArray?,
        sealer: KeyCopySealer,
    ): Result<PendingMembershipChange.Rejoin, GroupSyncError> {
        val key = try {
            client.epochKeyIn(mlsGroupId = rejoin.mlsGroupId, groupUuid = group.uuid)
        } catch (_: MlsException) {
            return Result.Error(GroupSyncError.Storage)
        }

        val keyCopy = sealer.seal(groupUuid = group.uuid, groupKey = key.key, keyGeneration = key.epoch)
            ?: return Result.Error(GroupSyncError.Vault(VaultError.Crypto))

        val link = previousKey?.let {
            sealKeyLink(previousKey = it, groupKey = key.key, groupUuid = group.uuid, generation = key.epoch)
        }

        return Result.Success(
            PendingMembershipChange.Rejoin(
                groupUuid = group.uuid,
                body = RejoinGroupBody(
                    nonce = newChangeNonce(),
                    commitMessage = Base64.Default.encode(rejoin.commit),
                    keyCopy = keyCopy,
                    previousKey = link,
                ),
                commitEpoch = key.epoch - 1,
            ),
        )
    }

    /**
     * Deletes again [mlsGroupId], the MLS group a rejoin just stored that the database is not to
     * name, see [attempt], or keeps it for the next pass to, see [undeleted].
     */
    private suspend fun deleteAgain(client: MlsClient, mlsGroupId: ByteArray) {
        if (!client.deletes(mlsGroupId = mlsGroupId)) undeleted += mlsGroupId
    }

    /**
     * Publishes the GroupInfo of every group the server lists in [listed] with
     * [GroupDto.groupInfoWanted], see [GroupRejoiner], that this device holds the MLS state of, the
     * user still in it, in the epoch the server lists it in, and with no change pending in it, and
     * returns the first failure: a group that fails is left for the next sync, and the next group is
     * seen to all the same.
     *
     * Not one the sync noted in [behind], short of where its members are, or about to move past it:
     * a GroupInfo of an epoch the group leaves is of use to nobody. Exported from the very copy of the
     * group whose epoch it checks, see [mls.rejoinGroupInfoIn], and the server refuses one of any
     * other epoch than its own, as it does one nobody asks for any more, which is no failure: see
     * [GroupApi.publishGroupInfo]. Neither is a `404`, as for a rejoin, see [rejoinUnderGroupsLock].
     *
     * Nothing here reads the vault but the client, the GroupInfo signed with the user's key in the
     * library, and nothing is stored.
     */
    internal suspend fun publishUnderGroupsLock(
        listed: List<GroupDto>,
        behind: Set<String>,
    ): EmptyResult<GroupSyncError> {
        val wanted = listed.filter { it.groupInfoWanted && it.uuid !in behind }

        // Most syncs find nobody asking.
        if (wanted.isEmpty()) return Result.Success(Unit)

        val stored = storedGroups().associateBy { it.uuid }

        val publishing = wanted.mapNotNull { listing ->
            val group = stored[listing.uuid]
                ?.takeIf { !it.isLeft && it.mlsGroupId != null && it.keyGeneration == listing.keyGeneration }
                ?: return@mapNotNull null

            if (hasPendingChange(group.uuid)) null else group to listing
        }

        if (publishing.isEmpty()) return Result.Success(Unit)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupSyncError.Identity(opened.error))
        }

        var failure: GroupSyncError? = null

        for ((group, listing) in publishing) {
            val exported = try {
                client.rejoinGroupInfoIn(mlsGroupId = checkNotNull(group.mlsGroupId))
            } catch (_: MlsException) {
                failure = failure ?: GroupSyncError.Storage

                continue
            }

            if (exported.epoch != listing.keyGeneration) continue

            when (val published = publishGroupInfo(group.uuid, exported.groupInfo)) {
                is Result.Success -> Unit
                Result.Error(NetworkError.NOT_FOUND) -> Unit
                is Result.Error -> failure = failure ?: GroupSyncError.Network(published.error)
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}

/**
 * What the library refusing a rejoin with [exception] comes to, see [GroupRejoiner.attempt]: a
 * GroupInfo signed by nobody this device trusts, passed over for the next; the user's storage
 * failing, which says nothing of it; or one refused for anything else, which another of the same
 * epoch would be too, the identity of a member pinned to another key among it.
 */
private fun refusalOf(exception: MlsException): Attempt =
    when {
        exception is MlsException.UntrustedGroupInfo -> Attempt.Untrusted
        exception.isRetryable() -> Attempt.Failed(failure = GroupSyncError.Storage)
        exception is MlsException.IdentityKeyMismatch -> Attempt.Refused(failure = GroupSyncError.IdentityChanged)
        else -> Attempt.Refused(failure = null)
    }

/**
 * Whether the group as the server lists it holds a current member besides [userUuid], the user:
 * somebody whose device can publish a GroupInfo for them to rejoin it from, see [GroupRejoiner].
 * Those invited who have not joined yet among them, who will once they join.
 */
internal fun GroupDto.hasOtherMemberThan(userUuid: String): Boolean = members.any { it.userUuid != userUuid }

/**
 * Deletes the group [mlsGroupId], see [deleteGroupOf], and says whether it is gone: not when the
 * user's storage failing kept it held.
 */
private suspend fun MlsClient.deletes(mlsGroupId: ByteArray): Boolean =
    try {
        deleteGroupOf(mlsGroupId = mlsGroupId)

        true
    } catch (_: MlsException) {
        false
    }

/** What rejoining one group came to, see [GroupRejoiner.rejoin]. */
private sealed interface Rejoining {

    /**
     * Rejoined, and sent: taken, refused or pending, as the sender acted on it, [failure] being what
     * the server refusing it for good came to, if it did.
     */
    class Sent(val failure: GroupSyncError?) : Rejoining

    /**
     * No GroupInfo to rejoin from yet, or none to trust, or none of the group as the server lists
     * it, [failure] saying more when it is worth telling: one to be asked for again, see
     * [GroupRejoinPass.unanswered].
     */
    class Unanswered(val failure: GroupSyncError?) : Rejoining

    /** No rejoin taken by the server yet, see [GroupRejoiner.rejoinUnderGroupsLock]: nothing to ask for. */
    data object Waiting : Rejoining

    /** Kept from being rejoined by [failure]. */
    class Failed(val failure: GroupSyncError) : Rejoining

    /** Kept from being rejoined by [failure], the key ring failing, which no other group fares better with. */
    class Stopped(val failure: GroupSyncError) : Rejoining
}

/** What rejoining a group from one GroupInfo came to, see [GroupRejoiner.attempt]. */
private sealed interface Attempt {

    /** Signed by nobody this device trusts, see [MlsException.UntrustedGroupInfo]: nothing deleted. */
    data object Untrusted : Attempt

    /** Refused for anything but its signer, [failure] saying more when it is worth telling. */
    class Refused(val failure: GroupSyncError?) : Attempt

    /** Not built, or built and deleted again, for [failure]. */
    class Failed(val failure: GroupSyncError) : Attempt

    /**
     * Saved, [change], and sent, with what the server refusing it for good came to, if it did: none
     * before it is sent.
     */
    class Sent(val change: PendingMembershipChange.Rejoin, val failure: GroupSyncError?) : Attempt
}
