package database.domain

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForCurrencyCode

actual fun currencyDisplayName(code: String): String =
    NSLocale.currentLocale.localizedStringForCurrencyCode(code) ?: code
