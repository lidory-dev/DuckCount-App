package database.data.repository

import database.domain.repository.CurrencyRepository
import database.data.local.dao.CurrencyDao
import database.data.mappers.toCurrencyEntity
import database.domain.Currency

class CurrencyRepositoryImpl(
    private val currencyDao: CurrencyDao,
) : CurrencyRepository {
    override suspend fun insert(currency: Currency) {
        currencyDao.insert(currencyEntity = currency.toCurrencyEntity())
    }

    override suspend fun update(currency: Currency) {
        currencyDao.update(currencyEntity = currency.toCurrencyEntity())
    }

    override suspend fun delete(currency: Currency) {
        currencyDao.delete(currencyEntity = currency.toCurrencyEntity())
    }
}
