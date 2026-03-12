package database.currency

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
