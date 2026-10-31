package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import kotlinx.coroutines.CancellationException
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.MlsClient
import util.NetworkError
import util.Result

/**
 * Edits the title and description of a group the user created, as they ask from its settings: the
 * one member whose versions of its information the others show, each signed with their MLS
 * identity, see [GroupVersion]. Its currency stays the one it was created in, signed with every
 * version all the same. The title and description are taken without the whitespace around them,
 * as a creation takes them, see [GroupCreator], for everything below.
 *
 * In this order, under [withGroupsLock] as a creation, a change to a group's members and the sync
 * are: the edit is sealed under the key of the epoch the group is in on this device, which a
 * Commit read or made alongside would move it past. The lock is not reentrant, so this is never
 * called from under it: the screens call it, the sync never does.
 * 1. The group is read again, as it stands now. One the user did not create is refused, see
 *    [GroupEditError.NotCreator], and so is one with a change waiting on the server, see
 *    [GroupEditError.ChangePending], and an edit to the title and description it shows already,
 *    see [GroupEditError.Unchanged], with nothing sent.
 * 2. The new title and description, with the group's currency, are signed by the user as the next
 *    revision, one more than the one this device shows, see [GroupEntity.infoRevision], and sealed
 *    under the group's key, see [GroupVersion.seal], of the generation the server stores it with,
 *    see [EditGroupBody.keyGeneration].
 * 3. It is sent, see [GroupApi.edit]. Taken, it is what this device shows from then on, and the
 *    server keeps the version it replaced in the group's history, see [GroupHistory]. Refused for
 *    its generation, the group having moved to a later epoch this device has yet to read, the
 *    groups are synced, with the lock let go of, and the edit made once more under the key the
 *    sync gives, see [GroupEditError.KeyNotCurrent]. Refused as outdated, the server holding a
 *    newer version, the version it lists is shown as the sync would show it, see [newerThan]. One
 *    with this edit's title and description is this very edit, sent before and taken, its answer
 *    lost, and saved again from the form that still showed it: it is made, and nothing more is
 *    sent, which would wake every member for nothing. Any other is one the edit is to be made
 *    again over, see [GroupEditError.Outdated]. Anything else leaves the group as it was on this
 *    device, whatever the server did: an edit is not sent again, and one it took whose answer was
 *    lost, or whose taking the caller going away cut short here, is shown once the next sync lists
 *    it, as the outdated one is.
 *
 * Nobody is told: the server wakes every other current member, whose devices show the new version
 * as they sync, see [GroupSync], which they refuse unless the user signed it. Nothing of it but its
 * key needs the group's MLS state, so a group brought back after a log-out is edited as well, until
 * a Commit takes the group past the epoch of the key its copy held: the user's identity is the
 * vault's, which outlives a log-out, see [MlsIdentityRepository].
 *
 * The edit can wait on a sync under way for as long as that sync takes, and the device can lock
 * meanwhile, which leaves nothing the vault holds readable: who is signed in, and the user's MLS
 * identity until it is opened in this process. Nothing is sent then, see
 * [GroupEditError.DeviceLocked], as for a change to a group's members, see
 * [GroupMembershipChanger].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] is [GroupDao.get], [hasPendingChange] is
 * [GroupDao.hasPendingChange], [client] is [MlsIdentityRepository.client], [send] and [listGroups]
 * are [GroupApi.edit] and [GroupApi.getGroups], [updateInformation] is
 * [GroupDao.updateInformation], [keyOf] is [GroupKeyChain.keyOf] and [syncGroups] is
 * [GroupSync.sync], what it read told about through [GroupSyncAnnouncer], or what a test stands in
 * for any of them.
 */
class GroupEditor internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val send: suspend (groupUuid: String, body: EditGroupBody) -> GroupEditAnswer,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val updateInformation: suspend (groupUuid: String, version: GroupVersion) -> Unit,
    private val keyOf: suspend (
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ) -> Result<ByteArray, GroupKeyChainError>,
    private val syncGroups: suspend () -> Unit,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupKeyChain: GroupKeyChain,
        groupSync: GroupSync,
        groupSyncAnnouncer: GroupSyncAnnouncer,
        mlsIdentityRepository: MlsIdentityRepository,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        group = groupDao::get,
        hasPendingChange = groupDao::hasPendingChange,
        client = mlsIdentityRepository::client,
        send = groupApi::edit,
        listGroups = groupApi::getGroups,
        updateInformation = { groupUuid, version ->
            groupDao.updateInformation(
                uuid = groupUuid,
                title = version.fields.title,
                description = version.fields.description,
                infoRevision = version.revision,
            )
        },
        keyOf = groupKeyChain::keyOf,
        // As for an expense, see GroupExpenseWriter: what it reads of the other groups is read
        // once, so it is told about here, or never.
        syncGroups = { groupSyncAnnouncer.announce(pass = groupSync.sync(contactsTakenIn = false)) },
    )

    /**
     * Gives the group [groupUuid] [title] and [description], each without the whitespace around
     * it, see [GroupEditor].
     */
    suspend fun edit(groupUuid: String, title: String, description: String): Result<Unit, GroupEditError> {
        val edited = editOnce(groupUuid = groupUuid, title = title.trim(), description = description.trim())

        if ((edited as? Result.Error)?.error != GroupEditError.KeyNotCurrent) return edited

        // Out of the lock, which the sync takes: once it read the Commits the group moved on by,
        // the edit is sealed under the key they lead to.
        syncGroups()

        return editOnce(groupUuid = groupUuid, title = title.trim(), description = description.trim())
    }

    /** Makes the edit once, under [withGroupsLock], see [GroupEditor]. */
    private suspend fun editOnce(groupUuid: String, title: String, description: String): Result<Unit, GroupEditError> =
        withGroupsLock {
            // Once the lock is held: the device may have locked as this waited for it, see above.
            val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupEditError.NoSession)
                is Result.Error -> return@withGroupsLock read
            }

            editLocked(selfUuid = selfUuid, groupUuid = groupUuid, title = title, description = description)
        }

    private suspend fun editLocked(
        selfUuid: String,
        groupUuid: String,
        title: String,
        description: String,
    ): Result<Unit, GroupEditError> {
        val held = group(groupUuid) ?: return Result.Error(GroupEditError.NotHeld)

        if (held.ownerUuid != selfUuid) return Result.Error(GroupEditError.NotCreator)

        if (held.title == title && held.description == description) return Result.Error(GroupEditError.Unchanged)

        if (hasPendingChange(groupUuid)) return Result.Error(GroupEditError.ChangePending)

        val client = when (val read = unlockedOrError { client() }) {
            is Result.Success -> when (val opened = read.data) {
                is Result.Success -> opened.data
                is Result.Error -> return Result.Error(GroupEditError.Identity(opened.error))
            }

            is Result.Error -> return read
        }

        val version = GroupFields(title = title, description = description, currency = held.currency)
            .signedAs(client = client, groupUuid = groupUuid, revision = held.infoRevision + 1)
            ?: return Result.Error(GroupEditError.Crypto)

        val sealed = version.seal(groupKey = held.groupKey, groupUuid = groupUuid)
            ?: return Result.Error(GroupEditError.Crypto)

        val body = EditGroupBody(
            revision = sealed.revision,
            keyGeneration = held.keyGeneration,
            title = sealed.fields.title,
            description = sealed.fields.description,
            signature = sealed.signature,
        )

        return when (val answer = send(groupUuid, body)) {
            GroupEditAnswer.Edited -> {
                updateInformation(groupUuid, version)

                Result.Success(Unit)
            }

            is GroupEditAnswer.KeyGenerationNotCurrent -> Result.Error(GroupEditError.KeyNotCurrent)

            GroupEditAnswer.RevisionNotNewer -> {
                val shown = refresh(held = held, client = client)

                // This very edit, sent before, its answer lost: saved again, it is made already.
                if (shown != null && shown.fields.title == title && shown.fields.description == description) {
                    Result.Success(Unit)
                } else {
                    Result.Error(GroupEditError.Outdated)
                }
            }

            is GroupEditAnswer.Failed -> Result.Error(GroupEditError.Network(answer.error))
        }
    }

    /**
     * Shows the version of the information of [held], a group the user created, that the server
     * lists, when it is newer than the one this device shows and the user signed it, under their
     * own key, see [newerThan], as the sync would, see [GroupSync]: once an edit is refused as
     * outdated, so that the next one is made over it. Returns that version, or null when there is
     * none to show: the listing failing leaves it to the next sync, which shows it then.
     */
    private suspend fun refresh(held: GroupEntity, client: MlsClient): GroupVersion? {
        val listed = (listGroups() as? Result.Success)?.data?.firstOrNull { it.uuid == held.uuid } ?: return null

        return listed.newerThan(group = held, creatorKey = client.signaturePublicKey()) { generation, opens ->
            (keyOf(held.uuid, generation, opens) as? Result.Success)?.data
        }?.also { version -> updateInformation(held.uuid, version) }
    }
}

/**
 * What [read], a read of the vault for an edit the user asked for, gives, or
 * [GroupEditError.DeviceLocked] when the device is locked: what the vault holds is read with keys a
 * locked device cannot use, and reading it throws an [IllegalStateException] then.
 */
private inline fun <T> unlockedOrError(read: () -> T): Result<T, GroupEditError> =
    try {
        Result.Success(read())
    } catch (exception: CancellationException) {
        // The caller going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        Result.Error(GroupEditError.DeviceLocked)
    }
