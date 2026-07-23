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

    // No fractional part, nothing to round.
    if (!trimmed.contains('.')) return trimmed

    return BigDecimal(trimmed)
        .setScale(scale, RoundingMode.HALF_UP)
        .toPlainString()
}

fun String.roundAmountWithSubunits(): String = roundTo(2)
fun String.roundAmountWithoutSubunits(): String = roundTo(0)

fun String.toValidNumber(): String {
    return if (this.isBlank()) "0"
    else if (this.first() == '.') "0$this"
    else this
}
