package channel

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ContactDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.DirectChannelEntity
import mls.KeyPackageApi
import mls.MlsIdentityRepository
import mls.createGroupWith
import mls.deleteGroupQuietly
import mls.holdsGroup
import mls.isRetryable
import mls.pinnedSignatureKeyFor
import mls.withPeerLock
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.NetworkError
import util.Result
import util.nowEpochMilliseconds
import kotlin.io.encoding.Base64

/**
 * The MLS group this device reaches one other user through, built on demand.
 *
 * A channel is not a contact, see [DirectChannelEntity]. It is whatever group the user and
 * a peer currently share, whether a contact request created it, the peer rebuilt it after
 * logging out, or this device had to build one because a one-time expense named somebody it
 * had no way to reach.
 *
 * Rebuilding on demand is what makes a log-out survivable at all. The vault restores who the
 * user's contacts are and the MLS signature key that makes them still recognisable to those
 * contacts, but never the groups: their state lived in the database the log-out wiped, and
 * MLS has no way back into a group you have left, since `lib-duckcount` builds its client
 * with external commits refused. A fresh key package and a fresh group is the only way
 * back, and this is where that happens.
 */
class DirectChannelRepository(
    private val directChannelDao: DirectChannelDao,
    private val contactDao: ContactDao,
    private val identityConflictDao: IdentityConflictDao,
    private val keyPackageApi: KeyPackageApi,
    private val mlsIdentityRepository: MlsIdentityRepository,
) {

    /**
     * The channel to [peerUuid], building one when this device holds none.
     *
     * The returned [DirectChannel.welcome] is non-null while the peer has not been seen in
     * the group yet, and every message sent on the channel has to carry it: sends to one
     * peer run side by side and can reach the server in either order, so any of them may be
     * the first the peer reads, and one that reached them without it would be unreadable,
     * acknowledged, and gone for good.
     *
     * A channel whose Welcome has gone unanswered for long enough gets a new group, built
     * from a new key package: the Welcome may have been sealed to one whose secret the
     * peer's own log-out wiped, and resending it could then never work. Its group is kept
     * as the previous one, see [DirectChannelEntity.previousMlsGroupId]: the peer may have
     * joined it all the same, and only their [ChannelJoined] has been lost or withheld. The
     * groups held
     * before move down the same window as with [adopt].
     */
    suspend fun ensureChannel(peerUuid: String): Result<DirectChannel, DirectChannelError> {
        val ownUserUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(DirectChannelError.NoSession)

        // Before anything reaches the network: asking the server for our own key package
        // would consume one of the twenty this device published for real peers.
        if (peerUuid == ownUserUuid) return Result.Error(DirectChannelError.Self)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(DirectChannelError.Identity(opened.error))
        }

        return withPeerLock(peerUuid = peerUuid) {
            // Before the stored channel too, which may predate the conflict. Checked once the
            // client is open, since opening it is one of the places a conflict comes to light.
            if (identityConflictDao.isBlocked(peerUuid = peerUuid)) {
                return@withPeerLock Result.Error(DirectChannelError.IdentityChanged)
            }

            // Read again inside the lock: the caller may have looked before waiting for it.
            val stored = directChannelDao.get(peerUuid = peerUuid)
            val storedGroupId = stored?.mlsGroupId

            if (storedGroupId != null && !stored.isWelcomeSpent()) {
                return@withPeerLock Result.Success(
                    DirectChannel(mlsGroupId = storedGroupId, welcome = stored.pendingWelcome),
                )
            }

            // Building would pin whatever identity the key package carries, so it is only done
            // for a peer already pinned: adding a contact pins them, and the vault gives their
            // pin back after a log-out. Otherwise the server would get to name them, and could
            // arrange that by keeping them out of the vault read that restores the pins.
            val pinned = try {
                client.pinnedSignatureKeyFor(peerUuid = peerUuid)
            } catch (_: MlsException) {
                return@withPeerLock Result.Error(DirectChannelError.Storage)
            }

            if (pinned == null) return@withPeerLock Result.Error(DirectChannelError.NotPinned)

            build(client = client, peerUuid = peerUuid, stored = stored)
        }
    }

    /**
     * Whether this device has any reason to have heard of [peerUuid]: they are a contact,
     * or there is a channel row for them because the two have exchanged something before.
     *
     * What gates acting on a Welcome that arrives unasked. Joining one costs a stored group
     * and pins whatever identity it carries, so it is not done on the say-so of a stranger
     * the server named. A true stranger's first Welcome is dropped; the answer is for this
     * device to build the channel itself, in the direction it controls.
     *
     * And what gates asking somebody for the key to an expense, see
     * [expense.OneTimeExpenseSync.requestMissingKeys], since the server names its creditor too.
     */
    suspend fun isKnownPeer(peerUuid: String): Boolean =
        directChannelDao.get(peerUuid = peerUuid) != null ||
            contactDao.get(userUuid = peerUuid) != null

    /** Records that one more message carried the channel's pending Welcome. */
    suspend fun noteWelcomeAttached(peerUuid: String) {
        directChannelDao.countWelcomeAttachment(peerUuid = peerUuid)
    }

    /**
     * Records that a message from [peerUuid] was read in [mlsGroupId], which is the only
     * proof this device gets of anything about the other side's state.
     *
     * Read in the current group, it says the peer joined it, and the Welcome has done its
     * work. Read in the group it replaced, it says nothing new: that group is kept precisely
     * so what the peer sends there still opens, see [DirectChannelEntity.previousMlsGroupId].
     * Read in any other, the peer is in a group this device holds but does not record as
     * the channel, the one its own contact request created when the answer arrives in it
     * for instance, or the oldest it keeps, about to be pushed out, and that group becomes
     * the channel, see [adopt].
     */
    suspend fun noteReadIn(client: MlsClient, peerUuid: String, mlsGroupId: ByteArray) =
        withPeerLock(peerUuid = peerUuid) {
            val channel = directChannelDao.get(peerUuid = peerUuid)

            when {
                channel?.mlsGroupId.contentEquals(mlsGroupId) -> directChannelDao.markJoined(peerUuid = peerUuid)

                channel?.previousMlsGroupId.contentEquals(mlsGroupId) -> Unit

                // Unless it went meanwhile: the message was read before this took the lock, and
                // a group recorded since may have pushed this one out and deleted it. Adopted,
                // a group that is gone would be the channel for good.
                client.holdsGroup(mlsGroupId = mlsGroupId) ->
                    adoptLocked(client = client, peerUuid = peerUuid, mlsGroupId = mlsGroupId)

                else -> Unit
            }
        }

    /**
     * Whether this device may tell [peerUuid] now that it joined a group of theirs, which it
     * then records it does: at most once an hour.
     *
     * A peer can make this device join a group with every message they send, and each answer
     * would spend the user's own allowance of messages, the one their key shares and
     * requests need. An honest peer who goes without keeps attaching their Welcome until
     * anything else from this device is read, or it runs out, see [ChannelJoined]. Recorded
     * with the channel rather than in memory, which the process a push wakes would lose.
     */
    suspend fun claimChannelJoined(peerUuid: String): Boolean =
        withPeerLock(peerUuid = peerUuid) {
            val channel = directChannelDao.get(peerUuid = peerUuid) ?: return@withPeerLock false

            val now = nowEpochMilliseconds()

            // A last one stamped after now, the clock having been set back since, counts as due:
            // read as recent, it would hold every answer back until the clock caught up.
            val sinceLast = channel.channelJoinedSentAt?.let { now - it }

            if (sinceLast != null && sinceLast in 0 until CHANNEL_JOINED_INTERVAL_MILLISECONDS) {
                return@withPeerLock false
            }

            directChannelDao.markChannelJoinedSent(peerUuid = peerUuid, sentAt = now)

            true
        }

    /**
     * Records [mlsGroupId], a group [peerUuid] is demonstrably in, as the channel to them:
     * one this device just joined from their Welcome, the one their contact request created,
     * or one they were just read in.
     *
     * The group they are in wins, always: they can certainly read it, while the one this
     * device held they may have lost to a log-out. Keeping ours would mean every later
     * message going somewhere they cannot follow, and them asking again for each one forever.
     *
     * The group it replaces is kept as the previous one rather than deleted, see
     * [DirectChannelEntity.previousMlsGroupId], and the one kept before it as the older one,
     * see [DirectChannelEntity.olderMlsGroupId]: nothing here can tell whether the peer
     * still sends in either. After both sides rebuilt at once one of them is the group they
     * send in, and a Welcome can be older than what they hold by the time it is read. What
     * still arrives in them opens, and the older one is adopted again, see [noteReadIn].
     *
     * The one kept before those goes. Three is all a peer gets to make this device hold:
     * every message they send can carry a Welcome to a new group, and joining each one would
     * otherwise leave it stored until a log-out.
     */
    suspend fun adopt(client: MlsClient, peerUuid: String, mlsGroupId: ByteArray) =
        withPeerLock(peerUuid = peerUuid) {
            adoptLocked(client = client, peerUuid = peerUuid, mlsGroupId = mlsGroupId)
        }

    private suspend fun adoptLocked(client: MlsClient, peerUuid: String, mlsGroupId: ByteArray) {
        val stored = directChannelDao.get(peerUuid = peerUuid)

        if (stored?.mlsGroupId.contentEquals(mlsGroupId)) return

        // They are in it, so nothing has to carry a Welcome, and one held for the group this
        // replaces would not open this one.
        record(
            client = client,
            peerUuid = peerUuid,
            stored = stored,
            mlsGroupId = mlsGroupId,
            pendingWelcome = null,
        )
    }

    /**
     * Records [mlsGroupId] as the channel to [peerUuid], the groups [stored] held moving down
     * behind it, newest first, and deletes the one that falls out of the three kept.
     *
     * Only ever under [withPeerLock]: the row is read before and written after, and two
     * writers at once would each drop the other's group from it, leaving one nothing names.
     */
    private suspend fun record(
        client: MlsClient,
        peerUuid: String,
        stored: DirectChannelEntity?,
        mlsGroupId: ByteArray,
        pendingWelcome: ByteArray?,
    ) {
        val held = listOfNotNull(stored?.mlsGroupId, stored?.previousMlsGroupId, stored?.olderMlsGroupId)
            .filterNot { it.contentEquals(mlsGroupId) }

        directChannelDao.upsert(
            DirectChannelEntity(
                peerUuid = peerUuid,
                mlsGroupId = mlsGroupId,
                previousMlsGroupId = held.getOrNull(0),
                olderMlsGroupId = held.getOrNull(1),
                pendingWelcome = pendingWelcome,
                pendingWelcomeSince = pendingWelcome?.let { nowEpochMilliseconds() },
                channelJoinedSentAt = stored?.channelJoinedSentAt,
            ),
        )

        // Once the row no longer names it: a deletion that fails leaves a group nothing
        // names, never a name for a group that is gone.
        held.drop(2).forEach { client.deleteGroupQuietly(mlsGroupId = it) }
    }

    /**
     * Builds a group with [peerUuid] and records it as the channel to them, the groups
     * [stored] held moving down behind it, see [record].
     */
    private suspend fun build(
        client: MlsClient,
        peerUuid: String,
        stored: DirectChannelEntity?,
    ): Result<DirectChannel, DirectChannelError> {
        val encoded = when (val consumed = keyPackageApi.consumeKeyPackage(userUuid = peerUuid)) {
            is Result.Success -> consumed.data.keyPackage

            is Result.Error -> return Result.Error(
                when (consumed.error) {
                    // They published none, or their account is gone. Neither is reachable,
                    // and the client can do nothing differently about either.
                    NetworkError.NOT_FOUND -> DirectChannelError.Unreachable

                    else -> DirectChannelError.Network(consumed.error)
                },
            )
        }

        val keyPackage = runCatching { Base64.Default.decode(encoded) }.getOrNull()
            ?: return Result.Error(DirectChannelError.Unreachable)

        val creation = try {
            client.createGroupWith(
                invitee = InviteeKeyPackage(userUuid = peerUuid, keyPackage = keyPackage),
            )
        } catch (exception: MlsException) {
            return Result.Error(
                when {
                    exception is MlsException.IdentityKeyMismatch -> DirectChannelError.IdentityChanged
                    exception.isRetryable() -> DirectChannelError.Storage
                    else -> DirectChannelError.Unreachable
                },
            )
        }

        val welcome = creation.welcomes.firstOrNull { it.userUuid == peerUuid }?.welcome

        if (welcome == null) {
            // A group the peer was never given a way into is of no use to anybody.
            client.deleteGroupQuietly(mlsGroupId = creation.mlsGroupId)

            return Result.Error(DirectChannelError.Unreachable)
        }

        record(
            client = client,
            peerUuid = peerUuid,
            stored = stored,
            mlsGroupId = creation.mlsGroupId,
            pendingWelcome = welcome,
        )

        return Result.Success(DirectChannel(mlsGroupId = creation.mlsGroupId, welcome = welcome))
    }
}

/** A group to reach a peer through, and the Welcome to attach until they are in it. */
class DirectChannel(
    val mlsGroupId: ByteArray,

    /** Null once the peer has been seen in the group, see [DirectChannelRepository]. */
    val welcome: ByteArray?,
)

/**
 * Whether this channel's Welcome has been carried often enough, or long enough, to stop
 * waiting for an answer and build a new group, see [DirectChannelRepository.ensureChannel].
 *
 * Both bounds are needed: a device that sends nothing would never reach the count, and one
 * that sends constantly would reach it before a peer on holiday ever opened the app.
 */
private fun DirectChannelEntity.isWelcomeSpent(): Boolean {
    if (pendingWelcome == null) return false

    if (welcomeAttachCount >= MAX_WELCOME_ATTACHMENTS) return true

    val since = pendingWelcomeSince ?: return false

    return nowEpochMilliseconds() - since >= WELCOME_LIFETIME_MILLISECONDS
}

/** How many messages may carry a Welcome before the channel gets a new group. */
private const val MAX_WELCOME_ATTACHMENTS = 20

/** How long a Welcome may go unanswered before the channel gets a new group. */
private const val WELCOME_LIFETIME_MILLISECONDS = 7L * 24 * 60 * 60 * 1000

/** How long after telling a peer it joined a group of theirs this device waits to tell them again. */
private const val CHANNEL_JOINED_INTERVAL_MILLISECONDS = 60L * 60 * 1000
