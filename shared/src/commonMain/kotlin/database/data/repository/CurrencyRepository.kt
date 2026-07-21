package database.data.repository

import database.data.local.dao.CurrencyDao
import database.data.mappers.toCurrencyEntity
import database.domain.Currency

class CurrencyRepository(
    private val currencyDao: CurrencyDao,
) {
    suspend fun insert(currency: Currency) {
        currencyDao.insert(currencyEntity = currency.toCurrencyEntity())
    }

    suspend fun update(currency: Currency) {
        currencyDao.update(currencyEntity = currency.toCurrencyEntity())
    }

    suspend fun delete(currency: Currency) {
        currencyDao.delete(currencyEntity = currency.toCurrencyEntity())
    }
}
