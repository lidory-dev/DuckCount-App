package database.data.mappers

import database.data.local.entity.CurrencyEntity
import database.domain.Currency

fun CurrencyEntity.toCurrency() = Currency(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)

fun Currency.toCurrencyEntity() = CurrencyEntity(
    uuid = uuid,
    name = name,
    symbol = symbol,
    hasSubunits = hasSubunits,
)
