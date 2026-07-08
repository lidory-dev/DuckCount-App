package database.domain.repository

import database.domain.Currency

interface CurrencyRepository {
    suspend fun insert(currency: Currency)

    suspend fun update(currency: Currency)

    suspend fun delete(currency: Currency)
}
