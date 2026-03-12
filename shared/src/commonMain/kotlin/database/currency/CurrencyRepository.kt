package database.currency

interface CurrencyRepository {
    suspend fun insert(currency: Currency)

    suspend fun update(currency: Currency)

    suspend fun delete(currency: Currency)
}
