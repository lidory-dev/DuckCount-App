package app.lidory.duckcount.expenseList.utils

import java.math.BigDecimal
import java.math.RoundingMode

fun String.formatAmountWithSubunits(): String {
    val replaced = this
        .replace(',', '.')
        .filter { it.isDigit() || it == '.' }
        .dropWhile { it == '0' }

    val dotIndex = replaced.indexOf('.')

    if (dotIndex == -1) return replaced

    val intPart = replaced.take(dotIndex)
    val fracPart = replaced.substring(dotIndex + 1)
        .take(3)
        .padEnd(2, '0')

    return "$intPart.$fracPart"
}

private fun String.roundTo(scale: Int): String {
    val trimmed = this.trim()

    require(trimmed.matches(Regex("""^-?\d*(\.\d+)?$"""))) {
        "Invalid number: '$this'"
    }

    // Nothing to write an amount from.
    if (trimmed.isEmpty() || trimmed == "-") return trimmed

    return BigDecimal(trimmed)
        .setScale(scale, RoundingMode.HALF_UP)
        .toPlainString()
}

fun String.roundAmountWithSubunits(): String = roundTo(2)
fun String.roundAmountWithoutSubunits(): String = roundTo(0)

/**
 * Whether [this], an amount field's text as typed, is one the field takes: digits and, in a
 * currency with subunits, one decimal separator, a dot or a comma as the keyboard has it, followed
 * by at most two of them. Anything else is refused rather than rewritten: rewriting what is being
 * typed would hand the keyboard a new text, and drop the keys already on their way to it.
 */
fun String.isAmountInput(hasSubunits: Boolean): Boolean =
    matches(if (hasSubunits) Regex("""^\d*([.,]\d{0,2})?$""") else Regex("""^\d*$"""))

/**
 * The amount [this], an amount field's text (see [isAmountInput]), stands for, as the form keeps
 * and saves it, the one way the library takes an amount: "12" and "12.00" are 12.00 in a currency
 * with subunits and "12" is 12 in one without, "12,5" is 12.50, "007" is 7.00, and "0" none yet.
 */
fun String.toAmount(hasSubunits: Boolean): String {
    val formatted = formatAmountWithSubunits()

    return when {
        formatted.isBlank() -> formatted
        hasSubunits -> formatted.roundAmountWithSubunits()
        else -> formatted.roundAmountWithoutSubunits()
    }
}

/**
 * [this], an amount field's text right after an edit of [previous], its decimals dropped along
 * with the decimal separator when the edit deleted that alone: 12.50 without its dot is 12, never
 * 1250.
 */
fun String.dropDecimalsIfSeparatorDeleted(previous: String): String {
    val separator = previous.indexOfFirst { it == '.' || it == ',' }

    if (separator == -1 || this != previous.removeRange(separator, separator + 1)) return this

    return previous.take(separator)
}

fun String.toValidNumber(): String {
    return if (this.isBlank()) "0"
    else if (this.first() == '.') "0$this"
    else this
}
