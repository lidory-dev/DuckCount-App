package group

import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultError

/** What KSafe throws as anything stored with a key a locked device cannot use is read. */
private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/**
 * The user's copies of the keys of their groups as the server keeps them for the length of a test,
 * see [GroupKeyCopies], with the key their key ring holds for
 * [vault.VaultEntryKey.GROUP_MASTER_KEY], which seals them.
 *
 * Every copy the server takes, with a creation, a key rotation or as `PUT
 * /groups/{group_uuid}/key-copy`, lands in [copies] as the API stores it, for a test to read back
 * with [keyOf].
 */
internal class ServerKeyCopies {

    /** The key the key ring holds for the copies. */
    val sealingKey: ByteArray = generateSymmetricEncryptionKey()

    /** The copy the server holds for the user, by group. */
    val copies = mutableMapOf<String, EncryptedFieldDto>()

    /** The groups a copy was put up for, in order, and how many times the key ring was read. */
    val putUp = mutableListOf<String>()
    var keyRingReads = 0

    /** Whether the key ring answers, and whether the device is locked, which has reading it throw. */
    var isKeyRingAnswering = true
    var isDeviceLocked = false

    /** What the server answers a copy put up, which it stores unless a test says otherwise. */
    var saveAnswer: EmptyResult<NetworkError> = Result.Success(Unit)

    /**
     * The groups the server no longer holds the user as a current member of, removed since it
     * listed them say, which it answers a copy put up for with a `404`, storing nothing.
     */
    val notMemberOf = mutableSetOf<String>()

    /** The key ring's key, as `VaultRepository.getOrCreatePartKey` reads it. */
    fun readSealingKey(): Result<ByteArray, VaultError> {
        check(!isDeviceLocked) { DEVICE_LOCKED }

        keyRingReads++

        return if (isKeyRingAnswering) {
            Result.Success(sealingKey)
        } else {
            Result.Error(VaultError.Network(NetworkError.UNREACHABLE))
        }
    }

    /** What the server makes of [keyCopy] put up for the group [groupUuid], see [GroupApi.saveKeyCopy]. */
    fun save(groupUuid: String, keyCopy: EncryptedFieldDto): EmptyResult<NetworkError> {
        putUp += groupUuid

        if (groupUuid in notMemberOf) return Result.Error(NetworkError.NOT_FOUND)

        if (saveAnswer is Result.Success) copies[groupUuid] = keyCopy

        return saveAnswer
    }

    /**
     * The key the server's copy for the group [groupUuid] holds, or null when it holds none, or one
     * that does not open as that group's.
     */
    fun keyOf(groupUuid: String): ByteArray? =
        copies[groupUuid]?.openKeyCopy(sealingKey = sealingKey, groupUuid = groupUuid)

    /** [groupKey] sealed as the user's copy of the key of the group [sealedFor], as their device seals it. */
    fun copyOf(groupKey: ByteArray, sealedFor: String): EncryptedFieldDto =
        checkNotNull(sealKeyCopy(sealingKey = sealingKey, groupUuid = sealedFor, groupKey = groupKey))

    /**
     * A [GroupKeyCopies] sealing under [sealingKey] and putting copies up here, for the groups this
     * device holds in [groups], each recorded as `GroupDao.markBackedUp` records it.
     */
    fun groupKeyCopies(groups: MutableMap<String, GroupEntity>) = GroupKeyCopies(
        sealingKey = ::readSealingKey,
        saveCopy = ::save,
        storedGroups = { groups.values.toList() },
        markBackedUp = { groupUuid -> groups[groupUuid]?.let { groups[groupUuid] = it.markedBackedUp() } },
    )
}

/** [this] as `GroupDao.markBackedUp` leaves it once the server took the copy of its key. */
internal fun GroupEntity.markedBackedUp() = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = true,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
