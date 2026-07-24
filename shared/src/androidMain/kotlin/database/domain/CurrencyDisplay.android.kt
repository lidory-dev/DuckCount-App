package database.domain

import java.util.Locale
import java.util.Currency as JavaCurrency

actual fun currencyDisplayName(code: String): String =
    runCatching { JavaCurrency.getInstance(code).getDisplayName(Locale.getDefault()) }
        .getOrDefault(code)
