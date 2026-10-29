package group

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import kotlinx.coroutines.CancellationException
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.MlsClient
import util.EmptyResult
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
 * are: the key rotation after a removal seals the version the server holds again, see
 * [GroupMembershipSender], and one built alongside an edit would put back the version the edit
 * replaced. The lock is not reentrant, so this is never called from under it: the screens call
 * it, the sync never does.
 * 1. The group is read again, as it stands now. One the user did not create is refused, see
 *    [GroupEditError.NotCreator], and so is one with a change waiting on the server, see
 *    [GroupEditError.ChangePending], and an edit to the title and description it shows already,
 *    see [GroupEditError.Unchanged], with nothing sent.
 * 2. The new title and description, with the group's currency, are signed by the user as the next
 *    revision, one more than the one this device shows, see [GroupEntity.infoRevision], and sealed
 *    under the group's key, see [GroupVersion.seal].
 * 3. It is sent, see [GroupApi.edit]. Taken, it is what this device shows from then on, and the
 *    server keeps the version it replaced in the group's history, see [GroupHistory]. Refused as
 *    outdated, the server holding a newer version, the version it lists is shown as the sync would
 *    show it, see [newerThan]. One with this edit's title and description is this very edit, sent
 *    before and taken, its answer lost, and saved again from the form that still showed it: it is
 *    made, and nothing more is sent, which would wake every member for nothing. Any other is one
 *    the edit is to be made again over, see [GroupEditError.Outdated]. Anything else leaves the
 *    group as it was on this device, whatever the server did: an edit is not sent again, and one
 *    it took whose answer was lost, or whose taking the caller going away cut short here, is shown
 *    once the next sync lists it, as the outdated one is.
 *
 * Nobody is told: the server wakes every other current member, whose devices show the new version
 * as they sync, see [GroupSync], which they refuse unless the user signed it. Nothing of it needs
 * the group's MLS state, so a group brought back after a log-out is edited as well: the user's
 * identity is the vault's, which outlives a log-out, see [MlsIdentityRepository].
 *
 * The edit can wait on a sync under way for as long as that sync takes, and the device can lock
 * meanwhile, which leaves nothing the vault holds readable: who is signed in, and the user's MLS
 * identity until it is opened in this process. Nothing is sent then, see
 * [GroupEditError.DeviceLocked], as for a change to a group's members, see
 * [GroupMembershipChanger].
 *
 * [userUuid] is [getAuthenticatedUserUuid], [group] is [GroupDao.get], [hasPendingChange] is
 * [GroupDao.hasPendingChange], [client] is [MlsIdentityRepository.client], [send] and [listGroups]
 * are [GroupApi.edit] and [GroupApi.getGroups], and [updateInformation] is
 * [GroupDao.updateInformation], or what a test stands in for any of them.
 */
class GroupEditor internal constructor(
    private val userUuid: () -> String?,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val send: suspend (groupUuid: String, body: EditGroupBody) -> EmptyResult<NetworkError>,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val updateInformation: suspend (groupUuid: String, version: GroupVersion) -> Unit,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
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
    )

    /**
     * Gives the group [groupUuid] [title] and [description], each without the whitespace around
     * it, see [GroupEditor].
     */
    suspend fun edit(groupUuid: String, title: String, description: String): Result<Unit, GroupEditError> =
        withGroupsLock {
            // Once the lock is held: the device may have locked as this waited for it, see above.
            val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupEditError.NoSession)
                is Result.Error -> return@withGroupsLock read
            }

            editLocked(
                selfUuid = selfUuid,
                groupUuid = groupUuid,
                title = title.trim(),
                description = description.trim(),
            )
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
            title = sealed.fields.title,
            description = sealed.fields.description,
            signature = sealed.signature,
        )

        return when (val sent = send(groupUuid, body)) {
            is Result.Success -> {
                updateInformation(groupUuid, version)

                Result.Success(Unit)
            }

            is Result.Error -> if (sent.error == NetworkError.CONFLICT) {
                val shown = refresh(held = held, client = client)

                // This very edit, sent before, its answer lost: saved again, it is made already.
                if (shown != null && shown.fields.title == title && shown.fields.description == description) {
                    Result.Success(Unit)
                } else {
                    Result.Error(GroupEditError.Outdated)
                }
            } else {
                Result.Error(GroupEditError.Network(sent.error))
            }
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

        return listed.newerThan(group = held, creatorKey = client.signaturePublicKey())
            ?.also { version -> updateInformation(held.uuid, version) }
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
