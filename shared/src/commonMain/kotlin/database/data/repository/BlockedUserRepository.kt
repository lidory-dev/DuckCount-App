package database.data.repository

import contact.ContactSyncError
import database.data.local.dao.BlockedUserDao
import database.data.local.entity.BlockedUserEntity
import database.domain.BlockedUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import push.WakeList
import util.EmptyResult
import util.Result
import vault.BlockList
import vault.VaultRepository

/**
 * The users the user blocked, see [BlockedUserEntity]: who they are, and the vault's copy of them.
 *
 * Blocking somebody is [ContactRepository.block]'s, for a contact, removed first, and
 * [ContactRequestRepository.blockSender]'s, for the sender of a request, refused first. Both record
 * it here once that is done, see [record]. Lifting it is the user's alone, see [unblock].
 *
 * Every change lands on the device first, and [backUp] carries it to the vault, as for the contacts,
 * see [ContactRepository]. A log-out takes the device's copy along, and [ContactRepository.restore]
 * puts the vault's back, see [putBack], before anything that needs to know who the user blocked:
 * reading the requests, see [ContactRequestRepository.refresh], and the messages, see
 * [message.DirectMessageSync].
 */
class BlockedUserRepository(
    private val blockedUserDao: BlockedUserDao,
    private val vaultRepository: VaultRepository,
    private val wakeList: WakeList,
) {
    /**
     * Serializes [backUp]. The list is sealed whole, so two writes from this device would conflict,
     * and the second one would fail for nothing.
     */
    private val backUpMutex = Mutex()

    /** Everybody the user blocked, as it changes. */
    fun getBlocked(): Flow<List<BlockedUser>> =
        blockedUserDao.getBlockedAsFlow().map { blocked ->
            blocked.map { BlockedUser(userUuid = it.userUuid, fullName = it.fullName) }
        }

    /** Whether the user blocked [userUuid], and has not unblocked them since. */
    suspend fun isBlocked(userUuid: String): Boolean = blockedUserDao.isBlocked(userUuid = userUuid)

    /** Everybody the user blocked, for checking a whole list in one read. */
    suspend fun getBlockedUuids(): Set<String> = blockedUserDao.getBlockedUuids().toHashSet()

    /**
     * Records that the user blocked [userUuid], whom they saw as [fullName], or blocks them again.
     * Their signals stop waking the user at once, see [WakeList]. The vault only gets it with the
     * next [backUp].
     */
    internal suspend fun record(userUuid: String, fullName: String?) {
        blockedUserDao.upsert(BlockedUserEntity(userUuid = userUuid, fullName = fullName))

        wakeList.rebuild()
    }

    /**
     * Lifts the block on [userUuid]: what they send from here on is taken in again, as from anybody
     * who is not a contact, a request of theirs included. Nothing they sent while blocked is,
     * acknowledged unread as it was, see [BlockedUserEntity.isUnblocked]. The vault only drops them
     * with the next [backUp].
     */
    suspend fun unblock(userUuid: String) {
        blockedUserDao.unblock(userUuid = userUuid)

        wakeList.rebuild()
    }

    /**
     * Carries to the vault every block and unblock it has not taken yet, in one write.
     *
     * Only the rows still as they were read are marked backed up, see [BlockedUserDao.markBackedUp]
     * and [BlockedUserDao.deleteUnblocked]: one changed during the write waits for the next one.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = blockedUserDao.getNotBackedUp()

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        val (unblocked, blocked) = pending.partition { it.isUnblocked }

        val update = vaultRepository.updateBlockList(
            blocked = blocked.associate { it.userUuid to it.fullName },
            unblocked = unblocked.mapTo(HashSet()) { it.userUuid },
        )

        if (update is Result.Error) return@withLock Result.Error(ContactSyncError.Vault(update.error))

        blocked.forEach { blockedUserDao.markBackedUp(userUuid = it.userUuid, fullName = it.fullName) }

        blockedUserDao.deleteUnblocked(userUuids = unblocked.map { it.userUuid })

        Result.Success(Unit)
    }

    /**
     * Puts back everybody [blockList] holds, for [ContactRepository.restore], as the vault holds
     * them. A row this device already has stays as it is: a block or an unblock made since the
     * log-out, which the next [backUp] carries.
     */
    internal suspend fun putBack(blockList: BlockList) {
        blockedUserDao.insertNew(
            blocked = blockList.blocked.map { (userUuid, fullName) ->
                BlockedUserEntity(userUuid = userUuid, fullName = fullName, isBackedUp = true)
            },
        )

        // Whoever came back blocked must not wake the user with a request.
        wakeList.rebuild()
    }
}
