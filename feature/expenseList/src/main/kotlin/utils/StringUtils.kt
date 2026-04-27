package utils

fun String.formatAmountWithSubunits(): String {
    val replaced = this.replace(',', '.').replace(" ", "")
    val parts = replaced.split('.')

    if (parts.size > 1) {
        return "${parts[0]}.${parts[1].take(3).padEnd(2, '0')}"
    }

    return replaced
}

fun String.roundAmountWithSubunits(): String {
    val trimmed = this.trim()
    val isNegative = trimmed.startsWith("-")
    val unsigned = if (isNegative) trimmed.removePrefix("-") else trimmed

    require(unsigned.matches(Regex("""^\d+(\.\d+)?$"""))) {
        "Invalid number: '$this'"
    }

    val dotIndex = unsigned.indexOf('.')
    val intPart: String
    val fracPart: String

    if (dotIndex == -1) {
        return trimmed
    } else {
        intPart = unsigned.substring(0, dotIndex)
        fracPart = unsigned.substring(dotIndex + 1).padEnd(3, '0').substring(0, 3)
    }

    val d1 = fracPart[0].digitToInt()
    val d2 = fracPart[1].digitToInt()
    val d3 = fracPart[2].digitToInt()

    var cent1 = d1
    var cent2 = if (d3 >= 5) d2 + 1 else d2

    if (cent2 >= 10) {
        cent2 = 0
        cent1 += 1
    }

    val finalInt = if (cent1 >= 10) {
        cent1 = 0
        intPart.toLong() + 1
    } else {
        intPart.toLong()
    }

    val sign = if (isNegative) "-" else ""
    return "${sign}${finalInt}.${cent1}${cent2}"
}

fun String.roundAmountWithoutSubunits(): String {
    val trimmed = this.trim()
    val isNegative = trimmed.startsWith("-")
    val unsigned = if (isNegative) trimmed.removePrefix("-") else trimmed

    require(unsigned.matches(Regex("""^\d+(\.\d+)?$"""))) {
        "Invalid number: '$this'"
    }

    val dotIndex = unsigned.indexOf('.')
    val intPart: String
    val fracPart: String

    if (dotIndex == -1) {
        return trimmed
    } else {
        intPart = unsigned.substring(0, dotIndex)
        fracPart = unsigned.substring(dotIndex + 1)
    }

    val finalInt = if (fracPart.first().digitToInt() >= 5) {
        intPart.toLong() + 1
    } else {
        intPart.toLong()
    }

    val sign = if (isNegative) "-" else ""
    return "${sign}${finalInt}"
}
