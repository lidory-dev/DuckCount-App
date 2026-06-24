package database.user

import kotlinx.coroutines.flow.Flow

interface UserRepository {

    suspend fun insert(user: User)

    suspend fun update(user: User)

    suspend fun delete(user: User)

    fun getAll(): Flow<List<User>>

    fun getAllFiltered(filter: String): Flow<List<User>>

    fun get(uuid: String): Flow<User>

    fun getFullName(uuid: String): Flow<String?>

    fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    suspend fun updateFullName(userUuid: String, fullName: String)
}
