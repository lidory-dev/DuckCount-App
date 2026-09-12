package database.data.repository

import database.data.local.dao.ContactRequestDao
import database.data.mappers.toContactRequestEntity
import database.data.remote.ContactApi
import kotlinx.coroutines.flow.Flow
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * The contact requests addressed to the user.
 *
 * The server is the only source: the local table is a cache, so the screens that count
 * requests keep working offline and across restarts.
 */
class ContactRequestRepository(
    private val contactRequestDao: ContactRequestDao,
    private val contactApi: ContactApi,
) {
    fun getPendingCount(): Flow<Int> {
        return contactRequestDao.getCountAsFlow()
    }

    /**
     * Brings the cache in line with what the server holds.
     *
     * A failure leaves the cache exactly as it was, so the caller keeps showing the
     * count it already had.
     */
    suspend fun refresh(): EmptyResult<NetworkError> {
        return when (val response = contactApi.getContactRequests()) {
            is Result.Error -> response

            is Result.Success -> {
                contactRequestDao.replaceAll(
                    contactRequests = response.data.requests.map { it.toContactRequestEntity() },
                )

                Result.Success(Unit)
            }
        }
    }
}
