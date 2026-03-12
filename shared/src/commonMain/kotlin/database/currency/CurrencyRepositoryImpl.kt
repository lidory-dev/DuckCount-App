package database.currency

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
