package database.domain

/**
 * The single source of truth for the currencies DuckCount ships with.
 *
 * Each entry's [Currency.name] holds the stable **ISO 4217 code** (e.g. "USD"), the value that is
 * persisted, sent to the server, and passed to the debt algorithm. The human-readable, translated
 * name shown in the UI is derived from that code at render time via [currencyDisplayName].
 *
 * The [Currency.uuid] field is the persisted identity, so it must not change between releases.
 */
object CurrencyCatalog {

    val defaultCurrencies: List<Currency> = listOf(
        Currency(uuid = "ce39145a-8f96-430e-9395-9cc3940ee374", name = "USD", symbol = "$", hasSubunits = true),
        Currency(uuid = "f4c08b14-5847-4d50-87b0-6ca4f3e961e1", name = "EUR", symbol = "€", hasSubunits = true),
        Currency(uuid = "b8231be3-0777-4b3d-9fd1-e1472a502335", name = "JPY", symbol = "¥", hasSubunits = false),
        Currency(uuid = "e3252a7e-bdaa-4fbd-9348-cb0c88ccf348", name = "GBP", symbol = "£", hasSubunits = true),
        Currency(uuid = "3d00aef7-c1cf-49a9-b305-65b435375f7a", name = "CNY", symbol = "¥", hasSubunits = true),
        Currency(uuid = "134e79f9-b738-40a8-af69-e84799188231", name = "CHF", symbol = "Fr", hasSubunits = true),
        Currency(uuid = "ac30e031-191a-4970-8e38-f08794d0f7c8", name = "CAD", symbol = "$", hasSubunits = true),
        Currency(uuid = "652121f9-2b17-4cb1-94cd-2816c22c5e66", name = "AUD", symbol = "$", hasSubunits = true),
        Currency(uuid = "cccb63ec-350c-4317-84eb-bfc03d951c25", name = "HKD", symbol = "HK$", hasSubunits = true),
        Currency(uuid = "a26690d1-a62d-460e-8c26-28c5f1160710", name = "SGD", symbol = "$", hasSubunits = true),
        Currency(uuid = "d575f22e-11f7-4c77-90b5-7eea2e8e51ab", name = "SEK", symbol = "kr", hasSubunits = true),
        Currency(uuid = "f5048dc3-f361-443f-a2aa-38f36b2fcd30", name = "NOK", symbol = "kr", hasSubunits = true),
        Currency(uuid = "d185a906-45cd-4ec8-b717-79616022569f", name = "DKK", symbol = "kr", hasSubunits = true),
        Currency(uuid = "4d6eadbc-94df-4663-9a51-0cffb4f02744", name = "KRW", symbol = "₩", hasSubunits = false),
        Currency(uuid = "fdf9ccfd-83ae-45c3-a7a3-9b5a3f264c7a", name = "INR", symbol = "₹", hasSubunits = true),
        Currency(uuid = "e03bf843-f919-4bd1-8fa6-27fad7b8d57c", name = "RUB", symbol = "₽", hasSubunits = true),
        Currency(uuid = "14eeff47-a225-479a-a3e9-4bf08a24df9d", name = "BRL", symbol = "R$", hasSubunits = true),
        Currency(uuid = "88183f41-358c-4d2e-975d-dc8064f2ffed", name = "ZAR", symbol = "R", hasSubunits = true),
        Currency(uuid = "07cc9422-b429-4689-8410-92102c76cc70", name = "MXN", symbol = "$", hasSubunits = true),
        Currency(uuid = "c0de674c-b2b0-4730-bc51-d71fdc746ffb", name = "TRY", symbol = "₺", hasSubunits = true),
        Currency(uuid = "63afd37b-3f7c-4fce-96f4-bcbc880d69eb", name = "PLN", symbol = "zł", hasSubunits = true),
        Currency(uuid = "455105f0-9d5c-4cef-88a7-61e60dcccacd", name = "THB", symbol = "฿", hasSubunits = true),
        Currency(uuid = "53c8169a-0b01-4385-b5b1-e0ef27ec0e9e", name = "IDR", symbol = "Rp", hasSubunits = true),
        Currency(uuid = "12e24e67-d219-4dee-8398-8a11df1b08bd", name = "NZD", symbol = "$", hasSubunits = true),
        Currency(uuid = "1bb6c61f-6dbf-405c-978f-e2de947685d5", name = "AED", symbol = "د.إ", hasSubunits = true),
        Currency(uuid = "409d7e84-8265-4994-9864-2d9361ffee83", name = "SAR", symbol = "﷼", hasSubunits = true),
    )

    /** Fallback currency used where no group/expense currency is available yet. */
    val defaultCurrency: Currency get() = defaultCurrencies.first { it.name == "EUR" }
}
