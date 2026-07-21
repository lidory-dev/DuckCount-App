package database.data.repository

import database.data.local.dao.UserDao
import database.data.mappers.toUser
import database.data.mappers.toUserEntity
import database.domain.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserRepository(
    // TODO: Add remote data source.
    private val userDao: UserDao,
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
        return userDao.getUserAsFlow(uuid = uuid).map { userEntity ->
            userEntity.toUser()
        }
    }

    fun getFullName(uuid: String): Flow<String?> {
        return userDao.getUserFullNameAsFlow(uuid = uuid)
    }

    fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userDao.getAllFromGroupFiltered(filter, groupUuid).map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    suspend fun updateFullName(userUuid: String, fullName: String) {
        userDao.updateFullName(userUuid, fullName)
    }
}
