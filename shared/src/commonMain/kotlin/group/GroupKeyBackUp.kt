package group

import database.data.local.dao.GroupDao
import util.EmptyResult
import util.Result
import util.onSuccess
import vault.VaultError
import vault.VaultRepository

/**
 * Backs the key of each of the user's groups up to the vault, see
 * [vault.VaultEntryKey.GROUP_MASTER_KEY]: the only copy of it to survive a log-out.
 *
 * A key is recorded as backed up only once the vault took it, see
 * [database.data.local.entity.GroupEntity.isBackedUp], so one that could not be is tried again by
 * the next sync, see [backUpPending]. Called under [withGroupsLock].
 */
class GroupKeyBackUp(
    private val groupDao: GroupDao,
    private val vaultRepository: VaultRepository,
) {

    /** Backs up [groupKey] as the key of the group [groupUuid], and records that the vault holds it. */
    suspend fun backUp(groupUuid: String, groupKey: ByteArray): EmptyResult<VaultError> =
        vaultRepository.setGroupKey(groupUuid = groupUuid, groupKey = groupKey).onSuccess {
            groupDao.markBackedUp(uuid = groupUuid)
        }

    /**
     * Backs up every group key the vault does not hold yet, stopping at the first that fails:
     * the vault failing for one fails for the next just the same.
     */
    suspend fun backUpPending(): EmptyResult<VaultError> {
        groupDao.getNotBackedUp().forEach { group ->
            val backedUp = backUp(groupUuid = group.uuid, groupKey = group.groupKey)

            if (backedUp is Result.Error) return backedUp
        }

        return Result.Success(Unit)
    }
}
