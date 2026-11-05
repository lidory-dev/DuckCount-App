package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import kotlinx.coroutines.CancellationException
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.NetworkError
import util.Result
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One version of a group's information as its history shows it, see [GroupHistory]: what its
 * title and description were, and when the server stored it. Checked against its creator as the
 * version the group shows is, see [isSignedByCreator].
 */
class GroupInformationVersion(
    /** Which version this is, see [GroupVersion]: no other version of the group shown shares it. */
    val revision: Long,

    val title: String,

    val description: String,

    /**
     * Whether this is the group as it was created, see [FIRST_GROUP_REVISION], rather than as an
     * edit of its creator's left it.
     */
    val isCreation: Boolean,

    /**
     * Whether this is the version the group is at now: the newest shown, which this device shows
     * too, or shows once its sync lists it, see [GroupSync], unless it is older than the one this
     * device shows, see [GroupEntity.infoRevision]. The server holds that one back then, as it
     * would to have the group go back to a version it replaced, which the group never does, see
     * [newerThan], and none of those shown is the current one.
     */
    val isCurrent: Boolean,

    /**
     * When the server stored it, as the group was created or the edit that made it was taken, in
     * milliseconds since the epoch: the server's word, which nothing signs, see
     * [GroupHistoryVersionDto.revisedAt].
     */
    val revisedAt: Long,
)

/**
 * Reads the history of a group's information, as any member still in it asks from its settings:
 * the versions of its title and description the server keeps, the current one and the last nine
 * its edits replaced, see [GroupApi.history]. Its information alone: the changes to its members
 * are no part of it.
 *
 * Kept by the server, and read from it every time, rather than kept on this device: every member
 * then sees the same list, and so does the user on every device and after every log-out, which a
 * copy here would not, a log-out taking it with it, and it holding no more than this device
 * happened to sync. A member added since sees the versions from before they joined: each is
 * sealed under the key of the epoch it was made in, see [GroupHistoryVersionDto.keyGeneration],
 * which the chain leads back to from the key they hold, see [GroupKeyChain]. And the server drops
 * the oldest as an edit comes in past them, which a copy would hold on to. Nothing tells a member of
 * the history: it is read as they open it.
 *
 * A version is shown only once it opens under the group's key of its generation and its creator
 * signed it, see [openSigned], as the version the group shows, see [GroupSync]: every member holds
 * the key, and could seal fields of their own, which the server could list. One that does not is
 * left out: those sealed under a key this device has yet to derive, in an epoch its sync has yet
 * to read the Commit leading to, which none may be left to show but, see
 * [GroupHistoryError.Unreadable]. A creator this device holds no pin for is taken at the versions'
 * word, as for the version the group shows, see [isSignedByCreator]: whatever opens is shown then, a
 * member's included. Newest first, in the order of the revisions the creator signed
 * them as, and not the server's, which nothing vouches for, each revision once: an edit saved again
 * after its answer was lost is signed again at the revision it was, see [GroupEditor], and the
 * server saw that version too as it refused it. The newest is the current one, unless the server
 * holds back the one this device shows, see [GroupInformationVersion.isCurrent].
 *
 * Read under no lock, as it writes nothing but the keys the chain leads back to, see
 * [GroupKeyChain]: an edit taken meanwhile in an epoch this device has yet to reach can have the
 * server answer with a version it cannot open yet, which opens once read again. Waiting on a sync
 * under way instead, which holds the lock across its requests to the server, would hold up the
 * screen for as long as those take.
 *
 * The device can lock as the user waits on it, which leaves nothing the vault holds readable: who
 * is signed in, and the user's MLS identity, which the creator's key is read from, until it is
 * opened in this process. Nothing is read then, see [GroupHistoryError.DeviceLocked].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] is [GroupDao.get], [client] is
 * [MlsIdentityRepository.client], [history] is [GroupApi.history] and [keyOf] is
 * [GroupKeyChain.keyOf], or what a test stands in for any of them.
 */
class GroupHistory internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val history: suspend (groupUuid: String) -> Result<GroupHistoryResponse, NetworkError>,
    private val keyOf: suspend (
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ) -> Result<ByteArray, GroupKeyChainError>,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupKeyChain: GroupKeyChain,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        group = groupDao::get,
        client = mlsIdentityRepository::client,
        history = groupApi::history,
        keyOf = groupKeyChain::keyOf,
    )

    /** The versions of the information of the group [groupUuid] its history shows, see [GroupHistory]. */
    suspend fun versions(groupUuid: String): Result<List<GroupInformationVersion>, GroupHistoryError> {
        val selfUuid = when (val read = unlockedOrError { userUuid() }) {
            is Result.Success -> read.data ?: return Result.Error(GroupHistoryError.NoSession)
            is Result.Error -> return read
        }

        val held = group(groupUuid)?.takeIf { !it.isLeft } ?: return Result.Error(GroupHistoryError.NotMember)

        val client = when (val read = unlockedOrError { client() }) {
            is Result.Success -> when (val opened = read.data) {
                is Result.Success -> opened.data
                is Result.Error -> return Result.Error(GroupHistoryError.Identity(opened.error))
            }

            is Result.Error -> return read
        }

        val creatorKey = try {
            client.creatorKeyOf(creatorUuid = held.ownerUuid, selfUuid = selfUuid)
        } catch (_: MlsException) {
            return Result.Error(GroupHistoryError.Storage)
        }

        val listed = when (val read = history(groupUuid)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(GroupHistoryError.Network(read.error))
        }

        // Each generation's key read once: most versions of most groups share one.
        val keys = mutableMapOf<Long, ByteArray?>()

        val opened = mutableListOf<Pair<GroupVersion, GroupHistoryVersionDto>>()

        for (sealed in listOf(listed.current) + listed.previous) {
            val groupKey = if (sealed.keyGeneration in keys) {
                keys[sealed.keyGeneration]
            } else {
                val read = keyOf(groupUuid, sealed.keyGeneration) { key ->
                    sealed.unseal(groupKey = key, groupUuid = groupUuid, currency = held.currency) != null
                }

                when (read) {
                    is Result.Success -> read.data

                    is Result.Error -> when (val error = read.error) {
                        is GroupKeyChainError.Network -> return Result.Error(GroupHistoryError.Network(error.error))

                        // Left out, as a version that does not open is.
                        GroupKeyChainError.NotHeld,
                        GroupKeyChainError.NotMember,
                        GroupKeyChainError.Unreadable,
                        -> null
                    }
                }.also { keys[sealed.keyGeneration] = it }
            } ?: continue

            sealed.openSigned(group = held, groupKey = groupKey, creatorKey = creatorKey)
                ?.let { version -> opened += version to sealed }
        }

        val versions = opened
            .sortedByDescending { (version, _) -> version.revision }
            .distinctBy { (version, _) -> version.revision }
            .ifEmpty { return Result.Error(GroupHistoryError.Unreadable) }

        return Result.Success(
            versions.mapIndexed { index, (version, sealed) ->
                GroupInformationVersion(
                    revision = version.revision,
                    title = version.fields.title,
                    description = version.fields.description,
                    isCreation = version.revision == FIRST_GROUP_REVISION,
                    isCurrent = index == 0 && version.revision >= held.infoRevision,
                    revisedAt = sealed.revisedAtMillis(),
                )
            },
        )
    }
}

/**
 * When the server stored this version, in milliseconds since the epoch, as the API writes it for
 * every version it lists, in UTC, to the millisecond, see [GroupHistoryVersionDto.revisedAt].
 */
@OptIn(ExperimentalTime::class)
private fun GroupHistoryVersionDto.revisedAtMillis(): Long = Instant.parse(revisedAt).toEpochMilliseconds()

/**
 * What [read], a read of the vault for the history the user asked for, gives, or
 * [GroupHistoryError.DeviceLocked] when the device is locked: what the vault holds is read with
 * keys a locked device cannot use, and reading it throws an [IllegalStateException] then.
 */
private inline fun <T> unlockedOrError(read: () -> T): Result<T, GroupHistoryError> =
    try {
        Result.Success(read())
    } catch (exception: CancellationException) {
        // The caller going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        Result.Error(GroupHistoryError.DeviceLocked)
    }
