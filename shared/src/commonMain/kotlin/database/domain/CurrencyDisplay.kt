package database.domain

/**
 * Resolves the localized, human-readable name of a currency from its ISO 4217 [code]
 * (e.g. "USD" -> "US Dollar" / "dollar des États-Unis").
 *
 * Backed by the platform's locale database so every currency is translated for free in the
 * current device language. Falls back to the [code] itself when it is not a recognized ISO code.
 */
expect fun currencyDisplayName(code: String): String
