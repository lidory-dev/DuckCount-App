package database.user

import kotlinx.coroutines.flow.Flow

interface UserRepository {

    suspend fun insert(user: User)

    suspend fun update(user: User)

    suspend fun delete(user: User)

    fun getAll(): Flow<List<User>>

    fun get(uuid: String): Flow<User>

    fun getFullName(uuid: String): Flow<String>
}
