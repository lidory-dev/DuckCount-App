package database.user

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserRepositoryImpl(
    // TODO: Add remote data source.
    private val userDao: UserDao,
) : UserRepository {

    override suspend fun insert(user: User) {
        userDao.insert(user.toUserEntity())
    }

    override suspend fun update(user: User) {
        userDao.update(userEntity = user.toUserEntity())
    }

    override suspend fun delete(user: User) {
        userDao.delete(userEntity = user.toUserEntity())
    }

    override fun getAll(): Flow<List<User>> {
        return userDao.getAllAsFlow().map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    override fun getAllFiltered(filter: String): Flow<List<User>> {
        return userDao.getAllFiltered(filter = filter).map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }

    override fun get(uuid: String): Flow<User> {
        return userDao.getUserAsFlow(uuid = uuid).map { userEntity ->
            userEntity.toUser()
        }
    }

    override fun getFullName(uuid: String): Flow<String?> {
        return userDao.getUserFullNameAsFlow(uuid = uuid)
    }

    override fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userDao.getAllFromGroupFiltered(filter, groupUuid).map { userEntities ->
            userEntities.map { it.toUser() }
        }
    }
}
