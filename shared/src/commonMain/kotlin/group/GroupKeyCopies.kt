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
 * once read: without these, nothing would open a group's fields again. Nor would the group sync
 * bring the group back, with no MLS state, to be rejoined from there, see [GroupRejoiner], whose
 * Commit can give a device from outside the group the key of the epoch it leads to alone: only a
 * copy of the epoch it is built in links that key back to the ones before, see
 * [RejoinGroupBody.previousKey], the members publishing that link otherwise.
 *
 * Each copy is the bare key, sealed under the user's [VaultEntryKey.GROUP_MASTER_KEY] key, which
 * only their key ring holds, and bound to its group and to the generation it is of the group's
 * keys, see [copyContextOf]. The server lists the user's own with each group, with that
 * generation, see [GroupDto.keyCopy] and [GroupDto.keyCopyGeneration], and never reads one. One
 * copy per group, of the newest key the user held as it went up: the older generations a copy
 * brought back does not hold are got back through the chain of links that leads back from it, see
 * [GroupKeyChain].
 *
 * The creator's copy goes up with the group as it is created, see [CreateGroupBody.keyCopy], and
 * every committer's with each Commit they make, see [AddGroupMembersBody.keyCopy], a member's
 * rejoin after a log-out included, see [RejoinGroupBody.keyCopy]. Every other member puts up their
 * own as they join the group, and as each Commit they read takes it to a new epoch, see [putUp].
 * The server drops a member's copy as a Commit removes them, or as they leave, see [GroupLeaver].
 *
 * [sealingKey] is [VaultRepository.getOrCreatePartKey] for [VaultEntryKey.GROUP_MASTER_KEY],
 * [saveCopy] is [GroupApi.saveKeyCopy], [storedGroups] and [markBackedUp] are [GroupDao.getAll] and
 * [GroupDao.markBackedUp], or what a test stands in for any of them.
 */
class GroupKeyCopies internal constructor(
    private val sealingKey: suspend () -> Result<ByteArray, VaultError>,
    private val saveCopy: suspend (
        groupUuid: String,
        keyCopy: EncryptedFieldDto,
        keyGeneration: Long,
    ) -> EmptyResult<NetworkError>,
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
     * What seals the user's copies of their groups' keys, for the server to keep with each, see
     * [KeyCopySealer]: read before the group or the Commit a copy goes with is built, so that a key
     * ring not answering stops it with nothing built, and nothing spent.
     *
     * The key ring holds the key they are sealed under before a copy can leave this device, see
     * [VaultRepository.getOrCreatePartKey]: one sealed under a key that exists only in memory would
     * open for nobody, ever.
     */
    internal suspend fun sealer(): Result<KeyCopySealer, VaultError> =
        when (val read = sealingKey()) {
            is Result.Success -> Result.Success(KeyCopySealer(sealingKey = read.data))
            is Result.Error -> read
        }

    /**
     * The key each of [groups] carries the user's copy of, with the generation the server lists it
     * as, by group UUID, of those whose copy opens as that group's key of that generation: one the
     * server moved there from another group does not, see [copyContextOf], nor one it lists as
     * another generation than its own, nor one tampered with, and each is left out, as though the
     * server held none.
     *
     * Reads the key ring only when one of them carries a copy: most syncs bring nothing back.
     */
    internal suspend fun open(groups: List<GroupDto>): Result<Map<String, GroupKeyGeneration>, VaultError> {
        val copies = groups.filter { it.keyCopy != null }

        if (copies.isEmpty()) return Result.Success(emptyMap())

        val key = when (val read = sealingKey()) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return Result.Success(
            copies.mapNotNull { group ->
                val keyGeneration = group.keyCopyGeneration ?: return@mapNotNull null

                group.keyCopy
                    ?.openKeyCopy(sealingKey = key, groupUuid = group.uuid, keyGeneration = keyGeneration)
                    ?.let { group.uuid to GroupKeyGeneration(generation = keyGeneration, key = it) }
            }.toMap(),
        )
    }

    /**
     * Puts up the user's copy of the key of every group the server lists, [listed], that this
     * device holds and the user is still in, see [GroupEntity.isLeft], when the listing shows none
     * of theirs, or this device does not know the one it shows to be of the key it holds, see
     * [GroupEntity.isBackedUp]: a group just joined, one the server dropped the copy of as its
     * creator removed the user and added them back, and one a Commit read took to a new epoch, see
     * [GroupSync].
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
            val copy = sealKeyCopy(
                sealingKey = key,
                groupUuid = group.uuid,
                groupKey = group.groupKey,
                keyGeneration = group.keyGeneration,
            ) ?: return Result.Error(GroupSyncError.Vault(VaultError.Crypto))

            when (val saved = saveCopy(group.uuid, copy, group.keyGeneration)) {
                is Result.Success -> markBackedUp(group.uuid)

                // Out of this group alone, see above: the next one is seen to all the same.
                Result.Error(NetworkError.NOT_FOUND) -> continue

                is Result.Error -> return Result.Error(GroupSyncError.Network(saved.error))
            }
        }

        return Result.Success(Unit)
    }
}

/** Seals the user's copies of their groups' keys under [sealingKey], the key their key ring holds for them. */
internal class KeyCopySealer(private val sealingKey: ByteArray) {

    /**
     * [groupKey], the generation [keyGeneration] of the keys of the group [groupUuid], sealed as the
     * user's copy of its key, or null when the key ring's key is not a 32-byte key.
     */
    fun seal(groupUuid: String, groupKey: ByteArray, keyGeneration: Long): EncryptedFieldDto? =
        sealKeyCopy(sealingKey = sealingKey, groupUuid = groupUuid, groupKey = groupKey, keyGeneration = keyGeneration)
}

/**
 * What the user's copy of the generation [keyGeneration] of the key of the group [groupUuid] is
 * bound to, see [sealBound].
 *
 * Every copy of theirs is sealed under the same key, so without it the server could list one
 * group's copy with another group, and it would open all the same, as the key of a group it is not.
 * Naming the generation does the same for the generation the server lists it as, see
 * [GroupDto.keyCopyGeneration]: the key would otherwise be taken for that of another generation,
 * under which nothing it was sealed for opens, and the group sealed under it as that one.
 */
private fun copyContextOf(groupUuid: String, keyGeneration: Long) = "group_key_copy:$groupUuid:$keyGeneration"

/**
 * Seals [groupKey], the generation [keyGeneration] of the keys of the group [groupUuid], under
 * [sealingKey] as the user's copy of its key, see [GroupKeyCopies], or returns null when
 * [sealingKey] is not a 32-byte key.
 */
internal fun sealKeyCopy(
    sealingKey: ByteArray,
    groupUuid: String,
    groupKey: ByteArray,
    keyGeneration: Long,
): EncryptedFieldDto? =
    sealBound(key = sealingKey, context = copyContextOf(groupUuid, keyGeneration), plaintext = groupKey)

/**
 * Opens this copy of the generation [keyGeneration] of the key of the group [groupUuid] with
 * [sealingKey], or returns null when it does not open as that group's key of that generation, see
 * [GroupKeyCopies.open].
 */
internal fun EncryptedFieldDto.openKeyCopy(sealingKey: ByteArray, groupUuid: String, keyGeneration: Long): ByteArray? =
    unsealBound(key = sealingKey, context = copyContextOf(groupUuid, keyGeneration), content = this)
