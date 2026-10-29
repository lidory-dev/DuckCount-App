package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultEntryKey
import vault.VaultError
import vault.VaultRepository
import vault.sealBound
import vault.unsealBound

/**
 * The user's copies of the keys of their groups, which the server keeps with each group: the only
 * copy of a group's key to survive a log-out. A log-out wipes the local database, and the device's
 * copy of each key with it, while the Welcome or the message that handed a key over is acknowledged
 * once read: without these, nothing would open a group's fields again.
 *
 * Each copy is the bare key, sealed under the user's [VaultEntryKey.GROUP_MASTER_KEY] key, which
 * only their key ring holds, and bound to its group, see [copyContextOf]. The server lists the
 * user's own with each group, see [GroupDto.keyCopy], and never reads one.
 *
 * The creator's copy goes up with the group as it is created, see [CreateGroupBody.keyCopy], and
 * with every key rotation they make, see [RotateGroupKeyBody.keyCopy]. Every other member puts up
 * their own as they join the group, and as they read a new key in it, see [putUp]. The server
 * drops a member's copy as its creator removes them, or as they leave, see [GroupLeaver].
 *
 * [sealingKey] is [VaultRepository.getOrCreatePartKey] for [VaultEntryKey.GROUP_MASTER_KEY],
 * [saveCopy] is [GroupApi.saveKeyCopy], [storedGroups] and [markBackedUp] are [GroupDao.getAll] and
 * [GroupDao.markBackedUp], or what a test stands in for any of them.
 */
class GroupKeyCopies internal constructor(
    private val sealingKey: suspend () -> Result<ByteArray, VaultError>,
    private val saveCopy: suspend (groupUuid: String, keyCopy: EncryptedFieldDto) -> EmptyResult<NetworkError>,
    private val storedGroups: suspend () -> List<GroupEntity>,
    private val markBackedUp: suspend (groupUuid: String) -> Unit,
) {
    constructor(
        vaultRepository: VaultRepository,
        groupApi: GroupApi,
        groupDao: GroupDao,
    ) : this(
        sealingKey = { vaultRepository.getOrCreatePartKey(part = VaultEntryKey.GROUP_MASTER_KEY) },
        saveCopy = groupApi::saveKeyCopy,
        storedGroups = groupDao::getAll,
        markBackedUp = { groupUuid -> groupDao.markBackedUp(uuid = groupUuid) },
    )

    /**
     * Seals [groupKey] as the user's copy of the key of the group [groupUuid], for the server to
     * keep with it.
     *
     * The key ring holds the key it is sealed under before the copy can leave this device, see
     * [VaultRepository.getOrCreatePartKey]: one sealed under a key that exists only in memory would
     * open for nobody, ever.
     */
    suspend fun seal(groupUuid: String, groupKey: ByteArray): Result<EncryptedFieldDto, VaultError> {
        val key = when (val read = sealingKey()) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return sealKeyCopy(sealingKey = key, groupUuid = groupUuid, groupKey = groupKey)
            ?.let { Result.Success(it) }
            ?: Result.Error(VaultError.Crypto)
    }

    /**
     * The key each of [groups] carries the user's copy of, by group UUID, of those whose copy opens
     * as that group's: one the server moved there from another group does not, see
     * [copyContextOf], nor one tampered with, and either is left out, as though the server held
     * none.
     *
     * Reads the key ring only when one of them carries a copy: most syncs bring nothing back.
     */
    suspend fun open(groups: List<GroupDto>): Result<Map<String, ByteArray>, VaultError> {
        val copies = groups.mapNotNull { group -> group.keyCopy?.let { group.uuid to it } }

        if (copies.isEmpty()) return Result.Success(emptyMap())

        val key = when (val read = sealingKey()) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return Result.Success(
            copies.mapNotNull { (groupUuid, copy) ->
                copy.openKeyCopy(sealingKey = key, groupUuid = groupUuid)?.let { groupUuid to it }
            }.toMap(),
        )
    }

    /**
     * Puts up the user's copy of the key of every group the server lists, [listed], that this
     * device holds and the user is still in, see [GroupEntity.isLeft], when the listing shows none
     * of theirs, or this device does not know the one it shows to be of the key it holds, see
     * [GroupEntity.isBackedUp]: a group just joined, one the server dropped the copy of as its
     * creator removed the user and added them back, and one whose creator handed over a new key in
     * it since, see [GroupSync].
     *
     * The listing decides, as for the copies of one-time expense keys, see
     * [expense.OneTimeExpenseKeyRepository.backUp], rather than a record of what was sent, which the
     * server can make stale by dropping a copy. The flag covers what the listing cannot tell: the
     * server reads nothing of a copy, so its listing says that there is one, not which key it holds.
     *
     * Each group is recorded as backed up once the server took its copy, see
     * [GroupDao.markBackedUp]. Stops at the first copy that does not go up: the key ring or the
     * server failing for one fails for the next just the same, and the next sync tries them all
     * again, the listing still showing none, or the group not backed up. A `404` is that group's
     * alone, though: the user is no current member of it any more, removed since it was listed say,
     * see [GroupApi.saveKeyCopy], which says nothing of the other groups, whose copies go up all the
     * same. It is left not backed up, for the next listing to decide about, and not reported: a
     * group the user is out of is owed no copy.
     */
    suspend fun putUp(listed: List<GroupDto>): EmptyResult<GroupSyncError> {
        val isCopyListed = listed.associate { it.uuid to (it.keyCopy != null) }

        val owed = storedGroups().filter { group ->
            val isListedWithCopy = isCopyListed[group.uuid] ?: return@filter false

            !group.isLeft && !(isListedWithCopy && group.isBackedUp)
        }

        // Most syncs find every copy up, and reading the key ring is a request.
        if (owed.isEmpty()) return Result.Success(Unit)

        val key = when (val read = sealingKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(GroupSyncError.Vault(read.error))
        }

        for (group in owed) {
            val copy = sealKeyCopy(sealingKey = key, groupUuid = group.uuid, groupKey = group.groupKey)
                ?: return Result.Error(GroupSyncError.Vault(VaultError.Crypto))

            when (val saved = saveCopy(group.uuid, copy)) {
                is Result.Success -> markBackedUp(group.uuid)

                // Out of this group alone, see above: the next one is seen to all the same.
                Result.Error(NetworkError.NOT_FOUND) -> continue

                is Result.Error -> return Result.Error(GroupSyncError.Network(saved.error))
            }
        }

        return Result.Success(Unit)
    }
}

/**
 * What the user's copy of the key of the group [groupUuid] is bound to, see [sealBound].
 *
 * Every copy of theirs is sealed under the same key, so without it the server could list one
 * group's copy with another group, and it would open all the same, as the key of a group it is not.
 */
private fun copyContextOf(groupUuid: String) = "group_key_copy:$groupUuid"

/**
 * Seals [groupKey] under [sealingKey] as the user's copy of the key of the group [groupUuid], see
 * [GroupKeyCopies], or returns null when [sealingKey] is not a 32-byte key.
 */
internal fun sealKeyCopy(sealingKey: ByteArray, groupUuid: String, groupKey: ByteArray): EncryptedFieldDto? =
    sealBound(key = sealingKey, context = copyContextOf(groupUuid), plaintext = groupKey)

/**
 * Opens this copy of the key of the group [groupUuid] with [sealingKey], or returns null when it
 * does not open as that group's, see [GroupKeyCopies.open].
 */
internal fun EncryptedFieldDto.openKeyCopy(sealingKey: ByteArray, groupUuid: String): ByteArray? =
    unsealBound(key = sealingKey, context = copyContextOf(groupUuid), content = this)
