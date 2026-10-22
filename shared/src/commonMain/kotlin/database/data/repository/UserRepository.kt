package database.data.repository

import app.lidory.duckcount.hashDiscoveryUsername
import database.data.local.dao.UserDao
import database.data.mappers.toUser
import database.data.mappers.toUserEntity
import database.data.remote.ReserveDiscoveryUsernameRequestBody
import database.data.remote.UserApi
import database.domain.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import util.EmptyResult
import util.NetworkError
import util.Result
import util.asEmptyDataResult

class UserRepository(
    private val userDao: UserDao,
    private val userApi: UserApi,
) {

    suspend fun insert(user: User) {
        userDao.insert(user.toUserEntity())
    }

    suspend fun update(user: User) {
        userDao.update(userEntity = user.toUserEntity())
    }

    suspend fun delete(user: User) {
        userDao.delete(userEntity = user.toUserEntity())
    }

    suspend fun remove(userUuid: String) {
        userDao.remove(userUuid = userUuid)
    }

    fun getAll(): Flow<List<User>> {
        return userDao.getAllAsFlow().map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    fun getAllFiltered(filter: String): Flow<List<User>> {
        return userDao.getAllFiltered(filter = filter).map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    fun get(uuid: String): Flow<User> {
        return userDao.getUserAsFlow(uuid = uuid).filterNotNull().map { userEntity ->
            userEntity.toUser()
        }
    }

    fun getFullName(uuid: String): Flow<String?> {
        return userDao.getUserFullNameAsFlow(uuid = uuid)
    }

    /** The same name, read once, hidden or not, see [UserDao.getFullNameEvenIfHidden]. */
    suspend fun getFullNameEvenIfHidden(uuid: String): String? {
        return userDao.getFullNameEvenIfHidden(uuid = uuid)
    }

    fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userDao.getAllFromGroupFiltered(filter, groupUuid).map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    /**
     * Reserves [username] as the discovery handle of the user identified by [userUuid].
     *
     * Only its digest leaves the device. An invalid handle never reaches the server:
     * callers are expected to have validated it, so this is reported as the same
     * [NetworkError.BAD_REQUEST] the server would answer with.
     */
    suspend fun reserveUsername(userUuid: String, username: String): EmptyResult<NetworkError> {
        val digest = hashDiscoveryUsername(username = username)
            ?: return Result.Error(NetworkError.BAD_REQUEST)

        return userApi.reserveDiscoveryUsername(
            userUuid = userUuid,
            body = ReserveDiscoveryUsernameRequestBody(username = digest),
        ).asEmptyDataResult()
    }
}
