package profile

import database.data.local.dao.UserDao
import database.data.local.entity.UserEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import util.NetworkError
import util.Result

/**
 * The versions of every user's profile the server would keep, one per key, none pruned, and
 * which is each one's latest: the first saved under the newest key, as the server orders them by
 * creation, so rewriting a version leaves it where it was.
 */
internal class InMemoryProfileStore(private val events: MutableList<String> = mutableListOf()) : ProfileStore {

    /** Each user's versions, keyed by the partial hash of the key each was sealed under, oldest first. */
    private val byUser = mutableMapOf<String, LinkedHashMap<String, UserProfileDto>>()

    /** The partial hash of every save, in order. */
    val saves = mutableListOf<String>()

    /** Whether saving fails, as a request that timed out would. */
    var failsSaving = false

    /** Whether reading fails, as a server out of reach would. */
    var failsReading = false

    private var held: HeldSave? = null

    /** The versions of [userUuid]'s profile, keyed by the partial hash of their keys, oldest first. */
    fun versionsOf(userUuid: String): Map<String, UserProfileDto> = byUser[userUuid].orEmpty()

    /** Holds the next save back until the test releases it, saying when it got there. */
    fun holdNextSave(): HeldSave = HeldSave().also { held = it }

    override suspend fun getUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
    ): Result<ServedUserProfileDto, NetworkError> {
        if (failsReading) return Result.Error(NetworkError.REQUEST_TIMEOUT)

        val versions = byUser[userUuid].orEmpty()
        val version = versions[encryptionKeyPartialHash] ?: return Result.Error(NetworkError.NOT_FOUND)

        return Result.Success(
            ServedUserProfileDto(
                fullName = version.fullName,
                phoneNumber = version.phoneNumber,
                bankDetails = version.bankDetails,
                isLatest = versions.keys.last() == encryptionKeyPartialHash,
            ),
        )
    }

    override suspend fun saveUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
        body: UserProfileDto,
    ): Result<Unit, NetworkError> {
        held?.let {
            held = null
            it.entered.complete(Unit)
            it.release.await()
        }

        events += "save $encryptionKeyPartialHash"
        saves += encryptionKeyPartialHash

        if (failsSaving) return Result.Error(NetworkError.REQUEST_TIMEOUT)

        byUser.getOrPut(userUuid) { LinkedHashMap() }[encryptionKeyPartialHash] = body

        return Result.Success(Unit)
    }
}

internal class HeldSave {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

/** Takes the user written to the local database, the one thing a profile upload asks of it. */
internal class UsersWritten : UserDao {
    val written = mutableListOf<UserEntity>()

    override suspend fun insert(userEntity: UserEntity) {
        written += userEntity
    }

    override suspend fun update(userEntity: UserEntity) = error("Not used here")
    override suspend fun delete(userEntity: UserEntity) = error("Not used here")
    override suspend fun remove(userUuid: String) = error("Not used here")
    override fun getAllAsFlow(): Flow<List<UserEntity>> = error("Not used here")
    override fun getAllFiltered(filter: String): Flow<List<UserEntity>> = error("Not used here")
    override fun getUserAsFlow(uuid: String): Flow<UserEntity?> = error("Not used here")
    override fun getUserFullNameAsFlow(uuid: String): Flow<String?> = error("Not used here")
    override suspend fun getFullName(uuid: String): String? = error("Not used here")
    override suspend fun getFullNameEvenIfHidden(uuid: String): String? = error("Not used here")
    override fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<UserEntity>> =
        error("Not used here")

    override suspend fun updateFullName(userUuid: String, fullName: String) = error("Not used here")
}
