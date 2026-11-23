package app.lidory.duckcount.android.ui.text

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * [amount], an amount as the library writes it, "1234.50" or "-98.67", written for the screen in the
 * currency whose ISO 4217 code is [currencyCode], the way [locale]'s language writes an amount of it:
 * "€1,234.50" in English and "1 234,50 €" in French, with two decimals when the currency
 * [hasSubunits] and none otherwise, as iOS writes it. With a sign in front of a positive amount too
 * when [withSign], "+€48.00", for what the user gets back.
 *
 * For the screen alone: nothing is ever read back from it, what the library, the server and
 * anything sealed take staying the amount as the library writes it. An amount that is no number,
 * or a code that names no currency, is written as it is next to [symbol].
 */
fun formatAmount(
    amount: String,
    currencyCode: String,
    symbol: String,
    hasSubunits: Boolean,
    withSign: Boolean = false,
    locale: Locale = Locale.getDefault(),
): String {
    val value = amount.trim().toBigDecimalOrNull()
    val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()

    if (value == null || currency == null) return "$amount $symbol"

    val decimals = if (hasSubunits) 2 else 0
    val format = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }

    if (withSign && value.signum() > 0 && format is DecimalFormat) {
        // Where the language puts a minus, "-€48.00" or "-48,00 €".
        val minus = format.decimalFormatSymbols.minusSign

        format.positivePrefix = if (minus in format.negativePrefix) {
            format.negativePrefix.replace(minus, '+')
        } else {
            "+" + format.positivePrefix
        }
    }

    return format.format(value)
}

/** [value] written as [formatAmount] writes an amount. */
fun formatAmount(
    value: BigDecimal,
    currencyCode: String,
    symbol: String,
    hasSubunits: Boolean,
    withSign: Boolean = false,
    locale: Locale = Locale.getDefault(),
): String = formatAmount(
    amount = value.toPlainString(),
    currencyCode = currencyCode,
    symbol = symbol,
    hasSubunits = hasSubunits,
    withSign = withSign,
    locale = locale,
)
